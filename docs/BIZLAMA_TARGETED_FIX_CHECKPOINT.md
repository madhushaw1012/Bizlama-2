# BizLaMa Targeted Fix Checkpoint

Updated: 2026-09-10 UTC

| ID | Requirement | Status | Evidence | Files changed | Tests | Remaining risk |
|---|---|---|---|---|---|---|
| 1 | Calculate priorities from queued orders | DONE | All demand inputs now select only tenant/location-scoped `QUEUED` rows; overlapping dish lines aggregate deterministically and disappear immediately after an order advances. | `DemandCalculationService.java`; `DemandRecommendationIntegrationTest.java` | `DemandRecommendationIntegrationTest`: 8 run, 0 failures, 0 errors. | Multiple active recipe versions remain separate safety-bound preparation records. |
| 2 | Order lifecycle (`QUEUED -> PREPARING -> DONE`) | DONE | Flyway maps legacy terminal rows to `DONE`; backend/UI expose valid next actions; transitions retain transactional history, activity and outbox behavior; duplicate terminal request is rejected without another history row. | `V19__targeted_order_lifecycle.sql`; `Order.java`; `OrderTransitionService.java`; `RecommendationActionService.java`; `OperationalRepository.java`; order frontend/API/tests and affected integration fixtures | H2: `OrderInventoryBoundaryIntegrationTest` 8/8 and `DemandRecommendationIntegrationTest` 8/8; PostgreSQL V19 gate 1/1; Chrome focused lifecycle spec 1/1; frontend build passes. | Automated production completion still correctly requires durable production evidence; manual UI completion does not attach a production action. |
| 3 | Feedback-driven dish experiments | DONE | Active-recipe feedback now saves activity/outbox and recalculates deterministic theme evidence in one transaction. Three matching comments create/update a proposal; approval is idempotent, records actor/time, starts only the experiment, and does not activate another recipe. | `FeedbackService.java`; `FeedbackExperimentService.java`; feedback/experiment controller, repository, response and frontend contract; feedback/PG integrity tests | H2 acceptance 2/2; PostgreSQL forced-audit-failure rollback 1/1; Chrome experiment API contract 2/2. | Theme extraction is deliberately deterministic and vocabulary-limited; AI is not required for correctness. |
| 4 | Record Activity intent routing | DONE | Deterministic routing returns INVENTORY_UPDATE, ORDER_CAPTURE, FEEDBACK_CAPTURE or UNKNOWN; previews show tenant-resolved entities/confidence; UNKNOWN creates no proposal; confirmations reuse purchase/order/feedback services inside the existing transaction and idempotency lock. | Event intent/router/records/controller/application service; shared `OrderApplicationService.java`; activity frontend/model; `KitchenEventIntentRoutingIntegrationTest.java` | Backend required fixtures 4/4; Chrome activity/API specs 3/3; frontend production build passes. | Deterministic grammar intentionally covers explicit common commands; ambiguous input asks for clarification rather than guessing. |
| 5 | Verify and repair Vertex AI integration | DONE | Receipt extraction and recommendation explanations are the only Vertex callers. Both use external project/location/model config and ADC-backed reusable clients; safe status reports DISABLED, CONFIGURED, REACHABLE, or DEGRADED from real bounded-request observations and never probes on status reads. Exact setup and smoke procedure are documented without claiming live reachability. | AI client/runtime status, both Vertex adapters/providers, system status/settings contract, Vertex operations doc | Fresh compile passed; combined fake-provider/status/fallback gate: 16 run, 0 failures, 0 errors. No paid call. | A live REACHABLE result still requires the documented authorized smoke request in the target project. |
| 6 | Harden Gemini usage | DONE | Both Vertex paths implement one shared fakeable client contract. Receipt output rejects unknown fields as well as invalid values; both operations record model, operation, latency, outcome, validation and classified error without prompt contents. Deterministic activity/feedback/order/demand paths remain model-independent. | Gemini model client/runtime status; receipt/explanation providers/adapters/tests; Vertex operations doc | Invalid JSON, missing fields, invented IDs, timeout, permission failure, disabled fallback and status states pass; consolidated backend 61/61, PostgreSQL 11/11, Chrome 4/4, frontend production build passed. | Runtime observations are process-local; durable receipt/explanation audits remain the system of record. |
| 7 | Reconcile persisted feedback into menu experiments | DONE | Tenant-scoped experiment reads now reconcile feedback loaded outside live capture. Three matching comments create a proposal; deleting evidence withdraws only an unapproved proposal; an active experiment retains its approved theme; permanent recipes remain unchanged. V21 adds conflict-safe repeated feedback for four demo dishes without lowering the threshold. | `FeedbackExperimentService.java`; `ExperimentService.java`; `FeedbackService.java`; feedback controller/repository; `V21__feedback_experiment_reconciliation_seed.sql`; H2/PostgreSQL migration and behavior tests | Focused H2 6/6; PostgreSQL 12/12; full backend 164/164; Chrome experiment API contract 2/2. | Theme extraction remains intentionally deterministic and vocabulary-limited; approval remains explicit. |

## Worktree preservation note

The repository was already dirty before this pass. Pre-existing tracked edits and untracked files were recorded with `git status --short --branch`; they will not be overwritten or reverted. This checkpoint is the first file created by the targeted pass.

## Current focus

All seven targeted requirements are DONE; no deployment or live service mutation was performed.

## Final verification

| Gate | Result |
|---|---|
| Fresh backend compilation | 151 main sources compiled; success |
| Targeted backend regression | 61 tests; 0 failures, 0 errors, 0 skipped |
| PostgreSQL 16.15 integrity | 12 tests; 0 failures, 0 errors, 0 skipped; Flyway V1-V21 |
| Complete backend regression | 164 tests; 0 failures, 0 errors, 0 skipped |
| Focused Chrome | 4 tests; 4 success |
| Frontend production build | success |
| Fake Vertex/status subset | 16 tests; 0 failures, 0 errors, 0 skipped; no paid calls |
| GCP config/bootstrap/deploy | static validation and both dry runs exited 0; no cloud calls |

The first infrastructure dry-run attempt used a stale disposable `/tmp` fixture and failed before any command with missing `PROPOSAL_WORKER_SERVICE`. The fixture was updated with current placeholder-only required keys; the unchanged repository validation and both dry runs then passed.
