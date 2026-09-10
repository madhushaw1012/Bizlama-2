# BizLaMa signal engine

An isolated Java 21 / Apache Beam 2.76.0 streaming module. It consumes a
dedicated Pub/Sub subscription, retains valid versioned envelopes as immutable
raw evidence, writes canonical facts and bounded features to BigQuery, routes
invalid or stale input to an error table, and publishes **proposal commands**.
It never writes inventory, orders, waste, menus, or recommendation decisions.

## Contract and flow

The input body is the complete canonical `OutboxEvent` serialized by
`PubSubOutboxPublisher`: `eventId`, `eventType`, `schemaVersion`, `kitchenId`,
`entityType`, `entityId`, `occurredAt`, `recordedAt`, nullable correlation and
causation IDs, `deduplicationKey`, JSON-string fields `payloadJson` and
`sourceMetadataJson`, and all delivery-state fields. Version 1 is accepted.
Both embedded JSON strings are parsed strictly. Pub/Sub attributes `eventId`,
`eventType`, `schemaVersion`, `kitchenId`, `entityType`, `entityId`, and
`occurredAt` must exactly match the body before facts are created.

Supported analytics events are `ORDER_INGREDIENT_DEMAND`,
`INVENTORY_PURCHASED`, `INVENTORY_CONSUMED`, `WASTE_RECORDED`,
`INVENTORY_EXPIRED`, `INVENTORY_REVERSAL`, `INVENTORY_CORRECTED`,
`DEMAND_CALCULATION_SNAPSHOT`, `RECOMMENDATION_DECIDED`, and
`RECOMMENDATION_OUTCOME_RECORDED`. Other structurally valid v1 domain events
are immutable raw-only evidence, not errors. Quantity-bearing payloads retain
their exact producer decimal and unit and also expose a canonical value:

| Input | Canonical value |
| --- | --- |
| `mg`, `g`, `kg` | grams (`g`) |
| `ml`, `l` | millilitres (`ml`) |
| `each`, `piece(s)`, `pc(s)` | `each` |

Unknown units produce `null` canonical values and data-quality flags; no value
or conversion factor is invented. Structurally invalid envelopes are error-only.
A structurally valid envelope with an invalid supported analytics payload is
retained raw and quarantined from facts with `INVALID_ANALYTICS_PAYLOAD`.
Every error records a processing-time `observedAt` value even when its event
timestamp is unavailable. The error table is partitioned by that observation
time, requires bounded partition filters, and retains 90 days; `eventTime`
remains nullable evidence rather than a made-up timestamp.

`occurredAt` is the Beam event timestamp and the default Pub/Sub timestamp
attribute. `recordedAt - occurredAt` is producer delay, not Beam lateness, and
does not exclude an event. Watermark position plus the explicit two-hour
allowed-lateness window decides whether an event contributes to features.
Raw and fact evidence still retain an event that is too late for a feature
bucket. Runner counter `DroppedDueToLateness` makes this observable. The default
online event-ID dedup horizon is 24 hours; bounded replay performs exact global
dedup and deterministically retains the earliest recorded envelope.

Features are independently keyed by `kitchenId + ingredientId` and bounded into
UTC-aligned 15-minute, one-hour, and daily horizons. They contain demand,
receipts, consumption, waste, expiry, reversal, correction, the latest
authoritative demand-calculation snapshot, decision/outcome counts,
quality-event counts, and a canonical unit. Streaming windows use two hours of explicit allowed lateness
and accumulating panes. Pane index/timing/finality make revisions auditable;
`signal_features_current` selects the latest revision.

The rules are deterministic and intentionally small:

- shortage: latest snapshot `grossDemand` exceeds its `usableSupply`;
- expiry surplus: latest snapshot `expiryRiskSurplus` is positive;
- material data quality: at least three flagged events, or at least two events
  with 25% or more flagged.

Each output is a versioned `CREATE_GOVERNED_RECOMMENDATION_PROPOSAL` command
with stable SHA-256-derived ID, evidence, `HIGH` risk tier, the configured
governed proposal Pub/Sub topic as `targetQueue`, and
`directMutationAllowed=false`. The queue consumer remains responsible for
governance, policy, identity, and idempotent proposal creation.

## Local verification

The tests use DirectRunner and cover valid canonicalization, duplicate IDs,
out-of-order input, too-late input, malformed input, kitchen isolation, all
three event-time horizons, deterministic proposals, missing-value quality
flags, and exact reconciliation.

```bash
docker run --rm \
  -v "$PWD":/workspace \
  -v bizlama-signal-m2:/root/.m2 \
  -w /workspace \
  maven:3.9-eclipse-temurin-21 mvn -B test
```

Build the executable shaded jar:

```bash
docker run --rm \
  -v "$PWD":/workspace \
  -v bizlama-signal-m2:/root/.m2 \
  -w /workspace \
  maven:3.9-eclipse-temurin-21 mvn -B package
```

### Clearly labelled synthetic replay

`src/test/resources/synthetic/multi-kitchen-replay.jsonl` is synthetic test
data only. Every valid line uses the complete producer envelope. It includes a
duplicate, out-of-order event, long producer delay, multiple kitchens,
unsupported raw-only event, invalid analytics payload, and malformed JSON.
True watermark lateness is covered with `TestStream`, not simulated from delay.

```bash
java -cp target/signal-engine-1.0.0-SNAPSHOT.jar \
  com.bizlama.signal.SyntheticReplayPipeline \
  --runner=DirectRunner \
  --inputFile=src/test/resources/synthetic/multi-kitchen-replay.jsonl \
  --outputPrefix=/tmp/bizlama-synthetic-replay \
  --reconciliationMode=true
```

The replay writes separate raw, fact, feature, error, and proposal JSONL files.
For production repair, write exact-replay fact/features to staging, review the
assertions in `bigquery/reconciliation.sql`, then run its fact and feature
partition replacement transaction. Full affected-partition replacement removes
pre-existing duplicates and stale feature buckets. The repair path never
derives canonical facts from ad-hoc SQL parsing.

Provide both staging tables together when running a bounded Dataflow replay:

```bash
--factStagingTable="${PROJECT_ID}:${DATASET}.signal_facts_reconciled_staging" \
--featureStagingTable="${PROJECT_ID}:${DATASET}.signal_features_reconciled_staging"
```

Staging uses `WRITE_TRUNCATE`. Run `bigquery/reconciliation.sql` with explicit
`@start_date` and `@end_date`; its uniqueness assertions execute before the
single fact-and-feature replacement transaction.

## GCP setup and deployment

Apply `bigquery/schema.sql` after substituting `PROJECT_ID` and `DATASET`.
Tables are partitioned on event/window time and clustered by kitchen and
ingredient. Set a short BigQuery partition/table expiration in non-production;
set the raw evidence retention period from governance policy in production.

Create a dedicated subscription so this consumer does not compete with another
subscriber, and create a proposal-only topic:

```bash
gcloud pubsub subscriptions create bizlama-signal-events-dataflow \
  --topic=bizlama-domain-events \
  --message-retention-duration=7d
gcloud pubsub topics create bizlama-governed-proposals
```

Build the Flex image and template spec (replace the example variables):

```bash
mvn -B package
gcloud builds submit --tag "${REGION}-docker.pkg.dev/${PROJECT_ID}/${REPOSITORY}/signal-engine:1.0.0" .
gcloud dataflow flex-template build "gs://${BUCKET}/templates/signal-engine.json" \
  --image "${REGION}-docker.pkg.dev/${PROJECT_ID}/${REPOSITORY}/signal-engine:1.0.0" \
  --sdk-language JAVA \
  --metadata-file metadata.json
```

Launch with Streaming Engine and conservative autoscaling. `e2-small` is an
initial low-volume benchmark choice, not a universal production sizing claim:

```bash
gcloud dataflow flex-template run "bizlama-signal-$(date +%Y%m%d-%H%M%S)" \
  --template-file-gcs-location "gs://${BUCKET}/templates/signal-engine.json" \
  --region "${REGION}" \
  --service-account-email "${DATAFLOW_SERVICE_ACCOUNT}" \
  --staging-location "gs://${BUCKET}/staging" \
  --enable-streaming-engine \
  --worker-machine-type e2-small \
  --num-workers 1 \
  --max-workers 3 \
  --parameters inputSubscription="projects/${PROJECT_ID}/subscriptions/bizlama-signal-events-dataflow",rawTable="${PROJECT_ID}:${DATASET}.signal_raw_events",factTable="${PROJECT_ID}:${DATASET}.signal_facts",featureTable="${PROJECT_ID}:${DATASET}.signal_features",errorTable="${PROJECT_ID}:${DATASET}.signal_pipeline_errors",proposalTopic="projects/${PROJECT_ID}/topics/bizlama-governed-proposals",timestampAttribute=occurredAt,idAttribute=eventId,allowedLatenessMinutes=120,deduplicationHorizonHours=24
```

The worker identity needs subscriber access on only the dedicated subscription,
publisher access on only the proposal topic, BigQuery job/write access for the
four tables, and object access to staging/temp paths. Keep template-builder and
runtime identities separate.

## Operational limits

- Online event-ID deduplication is best effort and bounded to 24 hours; only a
  bounded replay gives exact whole-history event-ID reconciliation.
- Producer delay is preserved for quality analysis but is never substituted for
  Beam watermark lateness.
- `DEMAND_CALCULATION_SNAPSHOT` is the authoritative usable-supply and expiry
  input. It replaces prior snapshots by event time and is never added to order
  demand.
- Feature panes are append-only revisions. Consumers should use the supplied
  current view or an equivalent primary-key merge.
- Storage Write API exactly-once mode is selected. Pub/Sub remains at-least-once,
  so stable proposal IDs and API idempotency remain mandatory.
- Deployment commands are provided but no GCP resources are created by this
  repository module.
- The Flex launcher is pinned to reviewed Java 21 tag `20260901-RC00`; update it
  deliberately after reviewing the Google release notes.

Relevant primary documentation: [Beam releases](https://beam.apache.org/get-started/downloads/),
[deduplication](https://beam.apache.org/releases/javadoc/current/org/apache/beam/sdk/transforms/Deduplicate.html),
[windowing and allowed lateness](https://beam.apache.org/documentation/programming-guide/#watermarks-and-late-data),
[Dataflow Beam support](https://cloud.google.com/dataflow/docs/support/sdk-version-support-status),
[BigQuery streaming writes](https://cloud.google.com/dataflow/docs/guides/write-to-bigquery), and
[Flex Template Java images](https://cloud.google.com/dataflow/docs/guides/templates/configuring-flex-templates).
