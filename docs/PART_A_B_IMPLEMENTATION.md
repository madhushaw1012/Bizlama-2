# Part A + Part B implementation ledger

This ledger is the resumable, evidence-based record for the autonomous BizLaMa
implementation. Status terms are limited to `IMPLEMENTED`, `VERIFIED_LOCAL`,
`VERIFIED_GCP`, `BLOCKED`, and `DEFERRED`. A status is only raised when the cited
evidence exists.

## Pass 0 — repository reconnaissance and baseline

### Observed state and evidence

- Baseline captured on 9 September 2026 at commit
  `306d06ee0d6c3be39b92a538208cb055eb9f79b0`, branch `main`, tracking
  `origin/main`. The worktree was clean before baseline commands.
- The historical review commit `25805edebf43518307a4d386a993433be85bab87`
  exists. The only source change since it is
  `V7__generic_recipe_experiments.sql` (24 added lines).
- No repository `AGENTS.md`, `PROJECT_HANDOFF.md`, or `STATUS.md` existed.
- Backend: Spring Boot 3.5.0, Java 21, Maven, JDBC, Flyway V1–V7. There is no
  Maven wrapper and no backend test source tree.
- Frontend: Angular 20.3, TypeScript 5.9, Node 22/npm 10. There are no tracked
  `*.spec.ts` files and no CI workflow.
- Local/default operational storage is file-backed H2. PostgreSQL is only
  configured indirectly through environment overrides or the Cloud SQL profile.
- The `analytics_outbox` table exists in V4 but is unused. Operational activity
  calls a synchronous, best-effort BigQuery `insertAll`; Pub/Sub, Dataflow,
  replay, DLQ, facts/features, reconciliation, and forecasts are absent.
- V7 is correctly named and applied successfully on a clean PostgreSQL 16
  database. It adds `metric_name` and `value_unit`, but the browser still expects
  butter-specific fields and the generic experiment audit/version contract is
  incomplete.
- Confirmed correctness defects at baseline:
  - today's revenue sums only `CANCELLED` orders;
  - persisted stock, movement, recipe, receipt, and parsed-event quantities use
    `double` in Java;
  - receipt and natural-language purchase paths contain an invented
    `purchaseDate.plusDays(7)` fallback;
  - receipt confirmation is neither atomic nor idempotent and trusts a complete
    client-resubmitted line proposal;
  - stock consumption has no kitchen/location/time scope, unit validation,
    quarantine/expiry exclusion, row lock, or conditional non-negative update;
  - accepted order lines do not retain an exact recipe-version reference;
  - high-risk natural-language actions may auto-apply from confidence alone;
  - all authenticated users may call every mutation; `/me` reports `OWNER`
    regardless of token authorities;
  - initial-recipe duplicate detection exists, but proposal duplicates fall
    through to a database error;
  - schema constraints/status domains and several causal foreign keys are absent;
  - dashboard restock logic uses fixed demo ingredient IDs and thresholds;
  - receipt upload validates only non-empty content;
  - `/api/system/status` reports enabled/configured flags as live connectivity;
  - SPA forwarding omits `/login` and `/settings`.

### Baseline commands and exact results

| Command | Result |
|---|---|
| `git status --short --branch` | exit 0; `## main...origin/main`; no changed paths |
| `git diff --stat 25805ed..HEAD` | exit 0; only V7, 24 insertions |
| `npm ci` | exit 0; 542 packages installed; audit reported 0 vulnerabilities |
| `npm run build` | exit 0; Angular production bundle generated; initial raw size 460.90 kB |
| `npm test -- --watch=false --browsers=ChromeHeadless` | exit 1; no spec bundle and no Chrome binary; no test was executed |
| host `java -version` / `mvn -version` | exit 127; Java and Maven absent |
| `docker run ... maven:3.9-eclipse-temurin-21 mvn -B test` | exit 0; 60 sources compiled; Maven reported no test sources |
| `docker build -t bizlama:pass0-baseline .` | exit 0; production image built; backend stage explicitly skipped tests |
| default image smoke | failed startup; non-root user could not create `/app/.data` for file H2 |
| image smoke with disposable PostgreSQL 16 | health `HTTP 200 {"status":"UP"}`; protected `/api/orders` returned 401 |
| PostgreSQL Flyway query | versions 1 through 7 present with `success=true`; V7 generic columns present |
| SPA route smoke | `/` 200, `/dashboard` 200, `/login` 404, `/settings` 404 |
| `bash -n infra/gcp/bootstrap.sh infra/gcp/deploy.sh` | exit 0 |
| `git diff --check` | exit 0 |

The disposable PostgreSQL/application containers and their isolated Docker
network were removed after evidence capture.

### Requirement matrix at baseline

| Requirement | Current evidence | Gap | Planned pass | Verification |
|---|---|---|---|---|
| Clean Flyway history | V1–V7 applied on clean PostgreSQL | upgrade constraints and data contracts missing | 1 | clean and upgrade PostgreSQL integration tests |
| Generic experiment contract | V7 columns exist | stale butter-specific UI; recipe/version/audit absent | 1 | API + Angular component tests |
| Decimal quantity/money integrity | DB uses decimal; money DTOs mostly `BigDecimal` | quantity DTOs/JDBC/calculation use `double` | 1 | unit/repository tests and source scan |
| Canonical unit conversion | base-unit strings and unused conversion table exist | no authoritative dimension-aware service | 1 | conversion compatibility tests |
| Expiry provenance | reviewed shelf-life table exists | invented fallback; no lot provenance or unresolved review | 1 | receipt/purchase expiry tests |
| Atomic receipt confirmation | separate repository methods exist | no outer transaction, lock, version, rollback, or idempotency | 1 | PostgreSQL success/rollback/replay tests |
| Receipt evidence validation | private local/GCS stores exist | no MIME/extension/size/signature gate | 1 | upload validation tests |
| Safe FEFO allocation | expiry ordering only | expired/cross-kitchen lots eligible; no locking or unit checks | 1 | PostgreSQL FEFO/concurrency/rollback tests |
| Revenue definition | summary query exists | sums cancelled orders | 1 | regression test |
| Backend roles and risk tiers | JWT authentication exists | no authorities, role policy, or high-risk approval boundary | 1 | security/API tests |
| Server-side proposals | browser confirmation flows exist | full parsed objects trusted; no persisted/versioned proposal | 1 | tamper/stale proposal tests |
| Immutable recipe-version orders | versioned recipes exist | order items only reference dish | 2 | historical-demand regression test |
| Deterministic demand evidence | demo dashboard expands active recipes | no horizon, exact versions, canonical supply, safety, or lot evidence | 2 | demand service tests |
| Governed recommendations/outcomes | fixed restock strings only | no persistence, decisions, actions, outcomes, reversal | 2 | service/API end-to-end tests |
| Operator recommendation card | dashboard shell exists | no recommendation/calculation/decision UX | 2 | Angular tests + production build |
| Transactional event envelope/outbox | incomplete unused V4 table | no transactional writer, envelope, claim/retry/replay | 3 | transaction + publisher contract tests |
| Pub/Sub raw path | absent | topic/subscriptions/publisher/raw consumer absent | 3 | local fake/contract test; cloud smoke when available |
| BigQuery raw/facts/features | incomplete DDL, mostly unwritten | required tables, transforms, dedup, reconciliation absent | 3 | SQL validation + mapping tests; cloud query blocked |
| Forecast/evaluation | absent | bounded forecast and fallback absent | 3 | deterministic tests; BigQuery execution blocked |
| Optional explanation provider | receipt extractor only | no explanation boundary, schema/audit/cache/fallback | 3 | provider/fallback tests |
| Beam/Dataflow pipeline | absent | entire Part B engine and DirectRunner proof absent | 4 | DirectRunner normal/duplicate/late/error tests |
| Multi-kitchen governed signals | kitchen columns exist | no windows/features/signals/callback boundary | 4 | synthetic multi-kitchen replay |
| Secure/cost-bounded GCP IaC | partial Cloud Run/SQL/GCS/BQ scripts | Pub/Sub/Dataflow/IAM/retention/ops incomplete | 5 | static checks; GCP execution blocked pending CLI/credentials |
| Full local verification | frontend build and PostgreSQL startup proven | no tests; default container broken; no smoke/CI | 6 | clean verify/build/container/smoke gates |
| GCP verification | historical checklist only | `gcloud` and `bq` absent; no live trace | 5–6 | `BLOCKED` until authenticated tooling/resources exist |

### Decisions and assumptions

- PostgreSQL remains the sole operational source of truth. H2 may remain only as
  an explicit convenience/fallback profile, never as evidence for PostgreSQL
  locking semantics.
- Existing migrations V1–V7 will not be rewritten. Corrective changes begin at
  V8.
- Demo seed records remain isolated in V2 and will not be referenced by new
  production calculation paths.
- Part A deterministic correctness is the gate. Part B infrastructure can be
  prepared later but cannot be used to mask an operational defect.
- All GCP claims remain unverified. No billable resource will be created merely
  from historical checklist text.

### Files changed in this pass

- Added this ledger only. No application, schema, frontend, or infrastructure
  implementation was changed during Pass 0.

### Remaining failures/blockers and next safe action

- Code defects: all confirmed correctness and trust-boundary issues listed above.
- Test defects: no backend or frontend tests exist.
- Toolchain: Java/Maven and Chrome are absent on the host; Docker provides Java
  21/Maven and will be used for reproducible verification.
- Cloud/configuration: `gcloud`/`bq` and authenticated live evidence are absent.
- Next: add V8 without modifying V1–V7, then implement BigDecimal/unit/expiry,
  atomic receipt confirmation, safe FEFO, revenue, proposal, and authorization
  services with PostgreSQL integration tests.


## Pass 1 — correctness and trust foundation

### Status

VERIFIED_LOCAL. Cloud-specific adapters remain covered by contract/failure tests rather
than live service calls.

### Implemented

- V8 adds decimal, canonical-unit, expiry-provenance, idempotency, proposal,
  recipe-version, status-domain, and causal integrity constraints without editing
  V1–V7.
- V14 establishes explicit kitchen/location ownership, foreign keys, inactive
  quarantine scope for ambiguous legacy records, and removes browser-selected
  tenant trust.
- V15 makes receipt extraction attempts and failures durable and recoverable.
- Persisted quantity and money flows use BigDecimal. UnitConversionService rejects
  incompatible dimensions and unsupported conversions rather than guessing.
- Stock purchase and receipt review require printed-date, reviewed-rule, or explicit
  owner-confirmed expiry provenance. The in-memory provider contains no automatic
  shelf-life values; unresolved expiry remains unresolved.
- FEFO allocation is kitchen/location/time scoped, excludes expired and quarantined
  lots, locks candidates, and uses conditional non-negative updates.
- Receipt upload checks size, signature, supported content type, and source quantity
  evidence. Review and confirmation are server-owned, version checked, atomic, and
  idempotent; failed storage/database sequences compensate safely.
- Effective application roles come from authenticated database membership. OWNER,
  ADMIN, MANAGER, and STAFF policies protect risk-tiered mutations; the browser is
  never authoritative for a confirmed proposal.
- Generic experiment fields replaced butter-specific production contracts. Revenue
  excludes cancelled orders. SPA forwarding includes login and settings.

### Verification evidence

- Final clean backend parent suite: 140 tests, 0 failures, 0 errors, 0 skipped.
- PostgreSQL integrity class: 10 tests on PostgreSQL 16.15; Flyway V1–V18.
- Receipt reliability focused suite: 10 tests, all passed.
- IAM/GCS focused selection: 24 tests, all passed.
- Angular: TypeScript spec compilation passed, production build passed, and 27
  Chrome Headless tests passed.
- Production-source scans found no double-based persisted quantity/money path,
  invented plusDays(7) expiry, disabled tests, or demo-specific entity names outside
  clearly labelled migrations and test fixtures.

## Pass 2 — deterministic operator loop

### Status

VERIFIED_LOCAL.

### Implemented

- V9 persists immutable demand calculations, ingredient and lot evidence, safety
  stock policy, recommendations, operator decisions, and outcome attribution.
- Accepted order lines retain the exact recipe version. Recipe versions carry
  explicit yield quantity/unit, author, approval, effective, and supersession audit.
- V17 classifies legacy recipe pins and yields. Untrusted records enter an owner/admin
  review queue and are excluded from exact demand, recommendations, and operational
  analytics until attested.
- Demand expands exact recipe-version quantities into canonical ingredient demand for
  an explicit horizon and relevant service time.
- Supply is computed only from safe, usable FEFO lots. Shortage and approaching-expiry
  surplus produce immutable calculation evidence and a governed recommendation.
- Recommendation transitions are version checked and idempotent: approve, edit,
  dismiss, flag inventory, apply, record outcome, and reverse. High-risk recipe
  activation remains an explicit authorised action.
- Dashboard cards expose quantity, unit, horizon, safe supply, shortage/surplus,
  assumptions, model/fallback provenance, approval state, action, and measured
  outcome. AI explanation is optional and cannot alter deterministic values.

### Verification evidence

- DemandRecommendationIntegrationTest: 7 tests passed in the final parent suite.
- Legacy provenance focused backend selection: 14 tests passed.
- PostgreSQL concurrency tests cover FEFO non-oversell and single active recipe
  version.
- The final Angular suite covers dashboard decisions, receipt review, activity
  proposals, order conflict handling, authentication, and API request contracts.

## Pass 3 — transactional events and Part A analytics

### Status

VERIFIED_LOCAL for application behavior, mappings, SQL contracts, and deployment
rendering. BLOCKED for authenticated Pub/Sub, BigQuery, Vertex, and scheduled-query
execution.

### Implemented

- V10 replaces synchronous best-effort analytics writes with a transactional outbox.
  Canonical envelopes contain event ID/type/version, kitchen/location, occurred and
  recorded times, source, correlation/causation IDs, and schema-valid payload.
- The dispatcher uses bounded claims, retry/backoff, dead-letter state, replay audit,
  and independent raw/Dataflow consumers. Producer occurrence time is distinct from
  ingestion time.
- V11 provides an ExplanationProvider boundary with deterministic fallback,
  input-hash cache, bounded structured output, provider/model/prompt metadata,
  latency/outcome/error audit, and no authority over calculations.
- V13 persists governed signal proposals as advisory, versioned, non-executable
  operator inbox records.
- V16 and V18 provide immutable inventory/order cutover baselines and one expected
  count manifest per migration-time kitchen/location, including zero-row scopes.
  Provenance review, mismatched counts, duplicates, missing facts, and unsupported
  rows remain quarantined.
- BigQuery DDL and scheduled SQL retain raw events; curate order demand, movement,
  waste, outcome, baseline, and scope-manifest facts; produce fail-closed daily
  snapshots; reconcile events and operational controls; and retain pipeline errors.
- Snapshot rows carry cutover_ready. A rerun marks prior operational rows false and
  restores only exact total, distinct fact ID, and distinct logical-key matches.
  Forecast, evaluation, and health queries require readiness.
- Forecasting is bounded to the five most active series, prioritises operational
  history, trains ARIMA_PLUS only with at least 14 observed days, emits a deterministic
  trailing-seven-day fallback otherwise, forecasts seven days, and persists intervals,
  version, generated time, history label, MAE, and WAPE.

### Verification evidence

- Operational baseline migration/integration: 4 tests passed; H2 fresh and populated
  V15-to-V18 paths validated all 18 migrations.
- Raw PostgreSQL 16 migration execution passed for fresh V1–V18 and populated
  V1–V15 then V16–V18, including canonical dedup and explicit zero scopes.
- Scheduler rendering produced one SQL terminator, no DECLARE, and no unresolved named
  parameter. Seven curation MERGEs have target-key and target-partition predicates.
- Local GCP configuration/static artifact validator passed with a disposable valid
  config. BigQuery parsing/execution is not claimed.

## Pass 4 — real-time multi-kitchen Dataflow signal engine

### Status

VERIFIED_LOCAL. BLOCKED for a live Dataflow job and cloud sinks.

### Implemented

- The isolated Java 21 Beam module validates versioned canonical envelopes, preserves
  immutable raw evidence, deduplicates by event ID, and distinguishes event,
  processing, and producer-delay time.
- It handles out-of-order data, bounded allowed lateness, late updates, canonical
  dimensions, quality flags, 15-minute/hour/day features, and deterministic shortage,
  expiry-risk, and data-quality signals per kitchen/location/ingredient.
- Dataflow writes raw, curated facts/features, governed proposal snapshots, and
  observed-at partitioned error rows. Invalid, unsupported, and policy-invalid input
  retains bounded reason/evidence without inventing quantities or identity.
- Governed proposals feed only the BizLaMa proposal inbox; Dataflow cannot mutate
  inventory, approve purchases, or activate recipes.
- Online deduplication is explicitly bounded to 24 hours. Bounded replay performs
  exact global dedup and stages complete partition replacement after reconciliation.

### Verification evidence

- Signal-engine clean Maven suite: 15 tests, 0 failures, 0 errors, 0 skipped; DirectRunner
  covers normal, duplicate, out-of-order, late, malformed, multi-kitchen, mapping, and
  replay/reconciliation contracts.
- Beam runtime dependency resolution completed and the production Dockerfile/config
  artifacts passed local static validation.

## Pass 5 — secure and cost-bounded GCP package

### Status

VERIFIED_LOCAL for convergent scripts, rendered commands, IAM intent, lifecycle,
monitoring, and runbooks. BLOCKED for resource creation because no approved
authenticated project, credentials, billing decision, gcloud, or bq are available.

### Implemented

- Explicit config names project, asia-south1 region/location, workspace IDs, immutable
  image tag, service names, bounds, and feature flags. Scripts are dry-run by default
  and require apply plus exact confirmations for mutations or teardown.
- Bootstrap defines required APIs, private Cloud SQL PostgreSQL 16 with backup/PITR,
  private receipt and Dataflow buckets, Artifact Registry, Pub/Sub fan-out, DLQ,
  BigQuery dataset/tables, and dedicated runtime/build/scheduler identities.
- API can read the database, owner-password, and token-signing secrets. Raw and proposal
  workers can read only the database secret; bootstrap removes stale worker access to
  the two authentication secrets. Secret values never appear in command arguments.
- Receipt readiness creates/reads a fixed-name private sentinel object. GCS writes are
  create-only and generation-specific compensation prevents overwriting or deleting
  another writer's evidence.
- Cloud Run API/raw/proposal services separate roles and consumers. Dataflow uses a
  dedicated worker identity, bounded e2-small workers, Streaming Engine, staging
  lifecycle, and an immutable Flex template.
- Monitoring covers outbox lag/failure/DLQ, subscriber health, pipeline errors,
  scheduled-query/forecast health, and backlog. Budget creation is optional and
  alert-only; retention policies preserve operational/audit truth.
- Incident, replay, rollback, backup, and explicit ephemeral teardown runbooks are
  present.

### Verification evidence

- bootstrap.sh dry-run exited 0 and rendered least-privilege IAM plus three API and one
  worker secret relationships; four stale worker auth-secret removals are explicit.
- deploy.sh dry-run exited 0 with API authentication secrets and database-only worker
  secrets.
- Shell syntax, JSON metadata/lifecycle, config, secret, health-query, and required
  artifact checks passed.
- No cloud command was executed in apply mode.

## Pass 6 — final verification and adversarial repair

### Status

VERIFIED_LOCAL. Live GCP verification remains BLOCKED.

### Repairs found by the parent gates

- Updated two stale test expectations from terminal Flyway V14 to V18.
- Upgraded test-only Testcontainers 1.21.0 to patch release 1.21.4 after Docker 29.4
  rejected its API 1.32 client; the focused PostgreSQL class and full parent suite then
  passed.
- Removed hardcoded production activity examples and recipe/receipt placeholders.
- Removed the automatic in-memory shelf-life map; memory mode now returns unknown.
- Added processing observed time to pipeline errors so null/malformed event time cannot
  bypass partitioning, retention, or incident lookback.
- Added per-series forecast health and operational-first selection, plus V18 readiness
  filters.
- Added convergent removal of stale worker access to authentication secrets and aligned
  deployment documentation.

### Aggregate local gates

| Gate | Exact result |
|---|---|
| Backend clean Maven test | 149 tests; 0 failures; 0 errors; 0 skipped; BUILD SUCCESS; 1:49 min (fresh dependency cache) |
| PostgreSQL focused integrity | PostgreSQL 16.15; 10 tests passed; Flyway V1–V18 |
| H2 V13 upgrade compatibility | 1 test passed; terminal version 18 |
| Frontend TypeScript specs | exit 0 |
| Angular production build | exit 0; 354.95 kB initial raw bundle; 8.509 s |
| Angular browser suite | Chrome Headless 152; 27 of 27 passed |
| Signal-engine clean Maven test | 15 tests; 0 failures/errors/skips; BUILD SUCCESS |
| Compose image/build | multi-stage Java 21 image built; non-root user bizlama |
| Compose HTTP/auth | readiness UP; four SPA routes 200; protected orders API 401 |
| Compose persistence | marker survived app and PostgreSQL container recreation |
| Local GCP static validation | exit 0; no cloud API called |
| Current backend source compile | 144 Java 21 sources compiled within the clean parent gate |

The browser runner required a no-sandbox wrapper only inside a credential-free,
disposable Docker test container because this host denies Chromium user namespaces.
Application/runtime browser configuration was not changed.

### Final hygiene closure

- `git diff --check` passed after all implementation and documentation changes.
- Repository source scans found no trailing whitespace, patch rejects, patch backups,
  hardcoded demo payloads, disabled focused tests, debug prints, or unsafe automatic
  expiry assignment.
- Every top-level GCP shell script passed `bash -n`; the static configuration validator
  passed again with a disposable non-secret configuration on 2026-09-10.
- The isolated verification stack, network, volumes, and locally built image were
  removed after the persistence check; no verification container remained running.

## Final changed-file map

| Area | Files and purpose |
|---|---|
| Schema | Forward-only V8–V18 migrations for integrity, demand/recommendations, outbox, explanation, governed signals, tenant scope, receipt reliability, cutover baseline, legacy provenance, and scope manifests |
| Backend | auth/workspace, quantity, stock/FEFO, receipts, recipes/orders, recommendations/outcomes, explanations, outbox/raw publisher, analytics health, governed signals, and system readiness |
| Frontend | typed API/model contracts and operator flows for authentication, activity proposals, receipts, recipes/provenance, orders, inventory, recommendations, outcomes, feedback, and settings |
| Dataflow | signal-engine Maven module, pipeline/mappings, synthetic replay, BigQuery schema/reconciliation, image, metadata, and operating guide |
| GCP | bootstrap/deploy/rollback/smoke, Cloud Build, lifecycle, SQL/schedules, reconciliation controls, monitoring, IAM/secrets, and incident/replay runbooks |
| Local runtime | Dockerfile, compose.yaml, environment example, ignore rules, README, and this ledger |
| Tests | 42 backend Java test sources, 11 Angular spec files, and 5 signal-engine Java test sources |

The removed synchronous BigQuery publisher package is intentionally replaced by the
transactional outbox plus independent raw and Dataflow consumers.

## GCP deployment sequence and configuration boundary

1. Copy infra/gcp/config.example.env outside source control and replace every explicit
   placeholder. Keep secrets in files outside the repository.
2. Run configure-secrets.sh in dry-run, then apply with exact project confirmation.
3. Run bootstrap.sh in dry-run, review resources/IAM/cost, then apply.
4. Build immutable application and Dataflow images through the dedicated build identity.
5. Run deploy.sh, apply BigQuery DDL, schedule bounded SQL, then deploy-dataflow.sh.
6. Run smoke-test.sh, operational and signal reconciliation, analytics health, and
   monitoring checks before accepting evidence.
7. Promote status to VERIFIED_GCP only after recording project/region, job/revision IDs,
   trace/event IDs, row/query evidence, timestamps, and costs without secrets.

The authoritative variable list, identities, roles, resource names, and commands are in
infra/gcp/config.example.env and infra/gcp/README.md. No service-account key file or
secret value belongs in the repository.

## Known limitations and exact blockers

- No authenticated BigQuery parse/dry-run, Pub/Sub publish/consume, Dataflow execution,
  Vertex request, scheduled query, Cloud Run deployment, IAM inspection, or cost trace
  has run. These remain BLOCKED, not failed and not VERIFIED_GCP.
- A location created after V18 needs an equivalent manifest-provisioning hook; until
  implemented it remains absent from operational snapshots by design.
- Existing daily snapshot rows receive nullable readiness during schema upgrade and
  need a bounded revalidation/backfill.
- Equal total/distinct counts cannot cryptographically prove an expected logical
  identity was not exchanged for an unexpected one. Uniqueness and event-specific
  audits mitigate this; a manifest identity digest would close it.
- Lifetime baseline/order retention increases scheduled snapshot scan volume. The
  operational reconciliation runner caps query bytes at 5 GB by default; scheduled
  snapshot queries do not have an equivalent hard byte cap.
- Online Dataflow event-ID dedup is bounded to 24 hours. Older duplicates require exact
  bounded replay and partition reconciliation.
- V2/V3/V5 historical fixtures are clearly named/labelled demo/reference migrations.
  New production calculations never hardcode their entity IDs.

## Rollback, replay, and recovery

- Application rollback routes traffic to a known immutable revision. Flyway remains
  forward-only; use a corrective migration if an older revision cannot read V18.
- Outbox replay is bounded by explicit event IDs/time scope and retains replay audit.
  Never delete PostgreSQL operational truth to repair analytics.
- Signal replay writes raw/fact/feature/error/proposal staging outputs, validates exact
  reconciliation, then replaces the full affected BigQuery partitions transactionally.
- Cloud SQL recovery restores backup/PITR to a separate instance first and reconciles
  immutable events before cutover.
- Dataflow rollback starts a previous immutable template as a new job and drains the
  old job; it never runs two jobs on one subscription expecting broadcast delivery.
- Exact procedures and confirmation flags are in
  infra/gcp/runbooks/replay-rollback.md.

## Five-minute reproducible demo

### Local operator loop

1. Run docker compose up --build and wait for both services.
2. Open http://localhost:8080, sign in with the clearly labelled local owner account,
   and confirm Settings shows database-backed role/workspace state.
3. In Receipt inbox, upload a supported receipt. With AI disabled it safely enters
   review; persist any missing line, choose canonical mapping and real expiry evidence,
   save the version, then confirm once. Retry confirmation to demonstrate idempotency.
4. In Orders, capture demand for an active dish and move the order through authorised
   status transitions. The exact recipe-version pin remains visible/auditable.
5. In Dashboard, calculate the bounded horizon, inspect safe FEFO supply and immutable
   lot evidence, generate shortage/expiry recommendations, approve or dismiss one,
   apply an approved safe action, and record/reverse an outcome.
6. In Activity, parse an update, review the server-owned versioned proposal, and
   confirm it once. The recent event and transactional outbox evidence share the
   trace/correlation chain.

The shortage and expiry-risk proof is also deterministic in
DemandRecommendationIntegrationTest; receipt replay/rollback and FEFO concurrency are
covered by the PostgreSQL suite.

### Part B evidence extension

1. Run the synthetic replay command documented in signal-engine/README.md and inspect
   its separate raw, fact, feature, error, and proposal JSONL outputs.
2. In an approved GCP deployment, publish one traceable canonical event, verify both
   raw and Dataflow subscriptions, query raw/fact/feature/error rows by event and trace
   IDs, inspect the governed proposal inbox, and run both reconciliation commands.
3. Query the seven-day forecast/evaluation and analytics health outputs. Label any
   synthetic history; do not present local replay as customer-scale or live-cloud proof.

## Next three highest-value actions after Parts A and B

1. Implement transactional provisioning of a V18-equivalent readiness manifest for
   every newly created location.
2. Add a manifest identity digest/list and a forecast-level invalidation marker to
   tighten delayed reconciliation.
3. Execute the documented GCP apply/smoke/reconciliation sequence in an approved
   project, capture trace/cost evidence, and only then promote VERIFIED_GCP.

## Post-implementation deployment reconciliation — 2026-09-10

### Main comparison

- The clean `feat/inital_eval` branch at `1d2701f` and fetched
  `origin/main` at `6b1006e` diverge from merge-base `306d06e`.
- Main contains one unique commit: `feat: allowing cors, origin paths, OPTIONS
  method`. Its Spring Security CORS enablement and anonymous API preflight
  allowance were already present on this branch.
- Main's remaining change was one project-specific Cloud Run URL plus explicit
  `OPTIONS` in MVC. `OPTIONS` was retained; the URL was superseded by validated
  configuration. Local defaults allow only loopback development origins, while
  cloud deployment derives the configured API service's regional Cloud Run
  pattern or accepts one explicit HTTPS browser origin. Wildcard-only origins
  such as `https://*` are rejected by both application and deployment validation.

### Vertex receipt extraction repair

- The previous extractor constructed an environment-dependent Google Gen AI
  client. Deployment did not select the Vertex backend, enable receipt AI, pass
  its model, or grant Vertex IAM for receipt-only use; therefore it was not
  deployably functional.
- The API now constructs one reusable client explicitly with Vertex enabled,
  configured project/location, attached Application Default Credentials, stable
  `v1` API, retryable status bounds, HTTP timeout, queue timeout, concurrency
  cap, and output-token cap.
- Receipt content stays private in GCS and is sent by `gs://` URI and validated
  MIME type. The model receives a response JSON schema and untrusted-data
  instruction. The application independently validates object shape, item count,
  text lengths, dates, positive quantities, non-negative prices/totals, and
  confidence range before persistence. No expiry is requested or inferred.
- `VERTEX_RECEIPTS_ENABLED` is independent of explanation enablement.
  Receipt-only deployment grants the API runtime `roles/aiplatform.user`; its
  existing receipt-bucket `roles/storage.objectUser` covers private evidence.
  Provider, schema, normalization, or persistence failures retain the object and
  enter durable retryable manual review.
- Settings now reads the nested receipt-AI property and accurately reports
  configured Vertex mode without claiming live connectivity.

### Verification evidence

| Gate | Exact result |
|---|---|
| Main refresh/comparison | `git fetch origin main`; fetched/local main both `6b1006e`; one main-only commit |
| Focused CORS/Vertex gate | 30 tests; 0 failures/errors/skips; BUILD SUCCESS; 8.127 s |
| Post-hardening CORS gate | 14 tests; 0 failures/errors/skips; wildcard-only Java configuration rejected |
| Status/PostgreSQL repair gate | 11 tests; 0 failures/errors/skips; PostgreSQL 16.15; Flyway V18; 16.285 s |
| Final clean backend parent | 149 tests; 0 failures/errors/skips; 144 main and 42 test sources; 1:49 min with fresh dependency cache |
| GCP static and receipt-only dry run | exit 0; Vertex role rendered; receipt flag/model/bounds and derived CORS origin rendered; wildcard-only origin rejected; no cloud call |
| Multi-stage image | built successfully; frontend bundle and Java 21 API packaged |
| Isolated runtime | readiness UP; SPA 200; protected API 401; allowed preflight 200; untrusted preflight 403; non-root `bizlama`; Flyway V18 |
| Cleanup | exact temporary containers, images, volumes, and network removed |

Status remains `VERIFIED_LOCAL`. An actual Vertex receipt request and deployed
Cloud Run preflight remain `BLOCKED` until the approved project, credentials,
tooling, billing, and private test receipt are available.
