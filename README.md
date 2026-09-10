# BizLaMa

BizLaMa is an AI-assisted kitchen operations workspace for small food businesses. It connects orders, versioned recipes, stock lots, expiry guidance, production, waste, customer feedback, and receipt evidence in one auditable workflow.

## What is implemented

- Persistent operational storage with version-controlled Flyway migrations.
- PostgreSQL as the operational database locally and in cloud mode, with H2 available only through the explicit non-persistent `memory` fallback profile.
- Branded-product normalization for Indian-market examples such as Amul Paneer and Heritage High Protein Paneer.
- Natural-language and browser voice capture for purchases, production, and waste.
- Multi-item natural-language capture, including shorthand such as `2 kg salt` and `12 eggs`, with one atomic review.
- Confidence policy: routine actions above 90% can apply automatically; lower-confidence actions need review; recipe changes always need explicit owner approval.
- Receipt upload, private local/GCS object storage, Vertex AI extraction, editable human review, and stock confirmation.
- Orders, stock lots and movements, feedback, recipe versions, experiments, and activity audit history persisted in the database.
- BigQuery operational event publishing for analytics, with failures isolated from operational transactions.
- One Cloud Run container serving both the Angular UI and Spring Boot API.
- Stateless owner authentication with protected APIs, short-lived bearer tokens, Secret Manager-backed cloud credentials, and an optional Google Identity Platform mode.
- Searchable recipe versions plus bounded editors for any number of ingredients and preparation steps.
- Accessible contextual tooltips on navigation, automation decisions, and important controls.
- Server-side search, status filtering, aggregate summaries, detail-on-demand and pagination for large inventories and order histories.
- An owner-only settings area keeps infrastructure status away from daily operational pages.
- Extensible kitchen, location, user, supplier, category, order-history and analytics-outbox schema with workload-specific indexes.

## Run locally

The normal local path uses PostgreSQL and persists both database data and local receipt evidence in named Docker volumes. Requirements: Docker with Docker Compose.

```bash
docker compose up --build
```

Open `http://127.0.0.1:8080` and sign in with:

- Email: `owner@bizlama.local`
- Password: `bizlama-demo`

Those credentials and the Compose database password are development-only. Copy `.env.example` to an untracked `.env` to override them. `docker compose down` stops the stack without deleting data; `docker compose down --volumes` deliberately deletes the local PostgreSQL and receipt volumes.

### Split frontend/backend development

Requirements: Java 21, Maven, Node.js 20+, and Docker. Start only PostgreSQL first:

```bash
docker compose up -d postgres
```

### Terminal 1

```bash
cd backend
JAVA_HOME=/path/to/jdk-21 mvn spring-boot:run
```

### Terminal 2

```bash
cd frontend
npm ci
npm start -- --host 127.0.0.1
```

Open `http://127.0.0.1:4200` and sign in with:

- Email: `owner@bizlama.local`
- Password: `bizlama-demo`

Those credentials are development-only and can be overridden with the variables in `.env.example`.

The backend's normal profile connects to `jdbc:postgresql://localhost:5432/bizlama`. The `bizlama-postgres-data` volume survives container and application restarts.

To deliberately use the non-persistent H2 fallback for a narrow local check, start the backend with:

```bash
JAVA_HOME=/path/to/jdk-21 mvn spring-boot:run -Dspring-boot.run.profiles=memory
```

The H2 fallback does not validate PostgreSQL-specific migrations, constraints, or row-locking behavior and is not the supported persistence path.

## Deploy to the prepared GCP project

Start from `infra/gcp/config.example.env`, copy it to a protected file outside
source control, replace every placeholder, and select whether receipt extraction
and recommendation explanations use Vertex AI. Then run the validated dry-run
sequence from the repository root:

```bash
export BIZLAMA_GCP_CONFIG=/secure/path/bizlama-gcp.env
infra/gcp/validate-config.sh
infra/gcp/bootstrap.sh --dry-run
infra/gcp/deploy.sh --dry-run
```

Review the rendered project, region, identities, resources, IAM, and cost before
running the explicit `--apply` sequence in
`infra/gcp/README.md`. The scripts are convergent and target only names in the
configuration file.

Bootstrap creates Secret Manager containers without values.
`configure-secrets.sh` reads values from protected files and does not print
them.

Flyway creates or upgrades the complete Cloud SQL schema when the new revision starts.

No service-account key file is needed. Each Cloud Run service uses its attached
least-privilege identity and Application Default Credentials. Set
`VERTEX_RECEIPTS_ENABLED=true` to enable schema-constrained receipt extraction;
the API identity then receives Vertex user access and already has object-user
access to the private receipts bucket. Failed extraction safely enters manual
review without inventing values or expiry dates.

The deployment supplies a service-specific Cloud Run CORS origin pattern. A
separately hosted browser can instead set one explicit HTTPS
`CORS_ALLOWED_ORIGIN_PATTERN`; arbitrary origins are not enabled.

## Production guardrails

- Never store database passwords or service-account JSON in source.
- Never deploy with the local demo password or development token secret. The GCP scripts inject generated values from Secret Manager.
- Keep receipt buckets private with public-access prevention.
- Printed expiry dates and owner overrides take precedence over shelf-life suggestions.
- Recipe proposals do not become active until the owner uses the activation action.
- The included single-owner authentication is appropriate for the current project and judging deployment.
- Tenant, user, location and role schema is already present for later onboarding: enable Identity Platform and enforce per-kitchen authorization before serving multiple independent businesses.

See `ARCHITECTURE.md` for data ownership, schema groups and large-list UX decisions.

## Optional Google Identity Platform mode

The backend can validate Identity Platform ID tokens and the Angular login can use email/password accounts directly.

Configure an Identity Platform email/password provider and web API key, then deploy with:

```bash
BIZLAMA_AUTH_MODE=identity-platform
BIZLAMA_GCP_PROJECT_ID=<your-project-id>
BIZLAMA_IDENTITY_API_KEY=<your-api-key>
```

The local mode remains the fastest secure path for the single-owner competition demo.
