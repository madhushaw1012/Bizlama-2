# Vertex AI and Gemini operations

## Active integration map

| Operation | Production behavior when enabled | Disabled or failed behavior |
|---|---|---|
| Receipt structured extraction | Gemini reads a private GCS receipt URI and returns schema-constrained merchant, purchase date, total, line, quantity, unit, price, and confidence data. Catalogue IDs and expiry are never model outputs. | Upload remains in durable manual review; stock is not changed until human review and confirmation. |
| Recommendation explanation | Gemini explains an immutable, redacted deterministic recommendation snapshot. It cannot change the authoritative quantity. | A deterministic explanation is returned and audited. |
| Record Activity classification | Not model-backed. The current router deterministically resolves tenant-scoped catalogue entities and requires confirmation. | Ambiguous input returns clarification and creates no proposal. |
| Feedback theme normalization | Not model-backed. The current threshold and supported themes are deterministic. | Feedback and experiment eligibility continue without Vertex. |

Both Vertex adapters implement the single `GeminiModelClient` boundary. Tests
replace that boundary with an in-process fake and never call a paid service.
Gemini never calculates demand, transitions orders, confirms stock, approves an
experiment, changes a permanent recipe, supplies catalogue identifiers, or
bypasses workspace authorization.

## Safety and diagnostics

Every request has a configured model, timeout, bounded retry count, concurrency
cap, output-token cap, JSON response schema, and application-side validation.
User or receipt text is explicitly treated as untrusted data. Logs contain only
operation, model, latency, outcome, validation result, and classified error;
they do not contain prompts, credentials, or provider error messages.

`GET /api/system/status` is the safe diagnostic path. It never calls Vertex:

- `DISABLED`: the feature flag is off.
- `CONFIGURED`: configuration loaded, but no request in this process has
  proved reachability.
- `REACHABLE`: the most recent bounded request and validation succeeded.
- `DEGRADED`: the most recent request failed and its safe fallback ran.

The nested diagnostic contains operation, model, latency, outcome, validation
result, error classification, and observation time. It is process-local; the
receipt processing and recommendation explanation audit tables remain durable.

## GCP setup

1. Enable the Vertex AI API in the application project:

   ```bash
   gcloud services enable aiplatform.googleapis.com --project=PROJECT_ID
   ```

   The repository bootstrap also enables the Cloud Run, Cloud Build, Artifact
   Registry, Cloud SQL, Secret Manager, Storage, logging, and monitoring APIs
   required by the complete BizLaMa deployment.

2. Use the dedicated Cloud Run identity
   `bizlama-api-runtime@PROJECT_ID.iam.gserviceaccount.com`. Grant it the
   minimum model-call role:

   ```bash
   gcloud projects add-iam-policy-binding PROJECT_ID \
     --member=serviceAccount:bizlama-api-runtime@PROJECT_ID.iam.gserviceaccount.com \
     --role=roles/aiplatform.user
   ```

   Receipt extraction also needs object access to the configured private receipt
   bucket. The bootstrap grants the API identity bucket-scoped
   `roles/storage.objectUser` because it stores and reads receipt evidence.
   Do not create or mount a service-account key.

3. Attach that identity to the API Cloud Run service. Cloud Run supplies
   Application Default Credentials:

   ```bash
   gcloud run services update bizlama-api \
     --project=PROJECT_ID \
     --region=REGION \
     --service-account=bizlama-api-runtime@PROJECT_ID.iam.gserviceaccount.com
   ```

4. Configure the runtime with environment variables, never credential values:

   | Purpose | Variable |
   |---|---|
   | Project | `BIZLAMA_GCP_PROJECT_ID` |
   | Vertex location | `BIZLAMA_VERTEX_LOCATION` |
   | Receipt enable/model | `BIZLAMA_VERTEX_AI_ENABLED`, `BIZLAMA_VERTEX_MODEL` |
   | Receipt bounds | `BIZLAMA_RECEIPT_AI_TIMEOUT`, `BIZLAMA_RECEIPT_AI_QUEUE_TIMEOUT`, `BIZLAMA_RECEIPT_AI_MAX_CONCURRENT`, `BIZLAMA_RECEIPT_AI_MAX_ATTEMPTS`, `BIZLAMA_RECEIPT_AI_MAX_OUTPUT_TOKENS` |
   | Explanation enable/model | `BIZLAMA_EXPLANATIONS_VERTEX_ENABLED`, `BIZLAMA_EXPLANATION_MODEL` |
   | Explanation bounds | `BIZLAMA_EXPLANATION_TIMEOUT`, `BIZLAMA_EXPLANATION_QUEUE_TIMEOUT`, `BIZLAMA_EXPLANATION_MAX_CONCURRENT`, `BIZLAMA_EXPLANATION_MAX_ATTEMPTS`, `BIZLAMA_EXPLANATION_MAX_OUTPUT_TOKENS` |

   Defaults are disabled. The repository deployment config uses
   `VERTEX_RECEIPTS_ENABLED`, `VERTEX_EXPLANATIONS_ENABLED`,
   `VERTEX_LOCATION`, and `VERTEX_MODEL` and maps them to the
   application variables above.

5. For local authorized testing, create user ADC and set the quota project:

   ```bash
   gcloud auth application-default login
   gcloud auth application-default set-quota-project PROJECT_ID
   ```

   Then set only the required non-secret variables and enable one feature. Local
   ADC is for developer testing only; Cloud Run must use its attached identity.

6. Use the validated deployment flow:

   ```bash
   export BIZLAMA_GCP_CONFIG=/secure/path/bizlama-gcp.env
   infra/gcp/validate-config.sh
   infra/gcp/bootstrap.sh --dry-run
   infra/gcp/deploy.sh --dry-run
   ```

   In that protected config, set the project/location/model and set either
   `VERTEX_RECEIPTS_ENABLED=true` or
   `VERTEX_EXPLANATIONS_ENABLED=true`. Review the rendered IAM and
   environment first. Run the corresponding `--apply` commands only with
   explicit deployment authorization.

   After that authorization, the API deployment sequence is:

   ```bash
   infra/gcp/bootstrap.sh --apply
   infra/gcp/configure-secrets.sh --apply
   infra/gcp/deploy.sh --apply
   ```

## Safe live smoke

Prefer the explanation path because it cannot change demand, stock, order, or
experiment state:

1. Use an authenticated, tenant-scoped, uncached disposable recommendation.
2. Read `GET /api/system/status`; the explanation entry should be
   `CONFIGURED`, not falsely `REACHABLE`.
3. Call `GET /api/recommendations/RECOMMENDATION_ID/explanation` with
   the normal application JWT. A protected Cloud Run service additionally
   requires an identity token in `X-Serverless-Authorization`.
4. Require response outcome `GENERATED`. A `CACHE_HIT` does not
   test provider reachability; use another labelled recommendation.
5. Read status again and require `REACHABLE`, matching model, successful
   validation, and bounded latency. Check the durable explanation audit row.
6. If the state is `DEGRADED`, use only the classified error and
   structured service log; do not log or copy the prompt.

Receipt-only smoke may upload a non-sensitive synthetic receipt. It must stop at
`REVIEW_REQUIRED` and must not confirm stock. Delete or retain the labelled
test receipt according to the approved retention policy.

## Disable and rollback

Set both feature flags to false and deploy a new Cloud Run revision:

```text
BIZLAMA_VERTEX_AI_ENABLED=false
BIZLAMA_EXPLANATIONS_VERTEX_ENABLED=false
```

With repository deployment config, set both `VERTEX_*_ENABLED` values to
`false` and redeploy. Status must show `DISABLED`; receipt manual
review and deterministic explanations remain available. If the application
revision itself is faulty, route traffic back to the prior known-good Cloud Run
revision. Remove `roles/aiplatform.user` only after no deployed revision
has either feature enabled.
