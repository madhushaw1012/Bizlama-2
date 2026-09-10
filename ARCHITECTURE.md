# BizLaMa production architecture

## Data ownership

| Information | System of record | Why |
|---|---|---|
| Orders, inventory, recipes, approvals, users | Cloud SQL for PostgreSQL | Transactional consistency, relationships, filtering and safe concurrent updates |
| Receipt images and source/import files | Private Cloud Storage buckets | Durable unstructured object storage |
| Historical events, trends and AI analysis | BigQuery | Large analytical scans without slowing daily operations |
| Passwords and signing keys | Secret Manager | Versioned secrets outside source and container images |

The Angular pages call the Spring Boot API. They never download a bucket and treat it as a database. The API reads daily data from Cloud SQL, stores receipt evidence in Cloud Storage, and publishes analytics events to BigQuery.

## Cloud SQL schema

Flyway applies the schema automatically when Cloud Run starts. Migrations `V4__production_scale_schema.sql` and `V5__shelf_life_rules.sql` add:

- Kitchens, users, roles and locations for future multi-location use.
- Suppliers, categories, unit conversions, reorder points, batch codes and unit cost.
- Versioned recipes with ordered ingredient and preparation-step children.
- Orders, order lines, channels, payment state and full status history.
- Stock lots and immutable stock movements for traceability and first-expiry-first-out use.
- Reviewed shelf-life rules with source, storage method, priority and review metadata; the raw FoodKeeper source is never used directly for automatic decisions.
- Receipt imports and extracted lines, AI decision audit, experiments and activity history.
- An analytics outbox table so reliable asynchronous publishing can replace direct publishing as volume grows.
- Composite indexes for kitchen, status, timestamp, ingredient and expiry filters.
- Database-backed menu categories used by the order register, with dishes assigned independently of their display position.

## BigQuery schema

`infra/gcp/bigquery-schema.sql` is applied by bootstrap and creates partitioned, clustered tables for operational events, orders, inventory snapshots, stock movements, feedback insights, AI audit, and raw shelf-life guidance.

Date filters should always be used in reporting queries so BigQuery prunes partitions.

## Large-list user experience

- Inventory and order history use server-side search, status filters and 25-row pages; only the current page is transferred.
- Summary cards come from database aggregate queries rather than loading every record into the browser.
- Order lines load only when a user opens the detail drawer.
- Creating a purchase or order is a separate focused workflow.
- Tables retain stable columns on desktop and scroll horizontally on small screens.
- Technical connection details live under owner-only Workspace settings, not daily operational pages.

## Deployment behavior

Local mode uses disk-backed H2 and local receipt files; the explicit memory profile is only a fallback/test mode.

Cloud mode uses the Cloud SQL Java connector, private GCS receipt storage, BigQuery and Vertex AI through the Cloud Run service account.

Flyway is the only schema-change mechanism. Never edit production tables manually.

Cloud Run receives pinned Secret Manager versions during each deployment.