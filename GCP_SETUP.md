# BizLaMa GCP setup tracker

Last updated: 7 September 2026

This file records the cloud setup in short, repeatable steps. It must never contain passwords, account emails, billing account identifiers, API keys, or secret values.

## Project decisions

- GCP project ID: `bizlama`
- Primary region: `asia-south1` (Mumbai)
- Monthly budget alert: INR 1,000
- Pub/Sub budget notifications: not enabled; email alerts are sufficient for the MVP
- Promotional credits are excluded from budget savings so usage before credits remains visible
- Free-trial credit available: USD 300

## Planned GCP services

- Cloud Run: host the Angular frontend and Spring Boot API in one same-origin container
- Cloud SQL for PostgreSQL: live inventory, orders, recipes, product mappings, and stock movements
- BigQuery: public dataset staging, transformations, and analytics
- Cloud Storage: source datasets and receipt images
- Vertex AI: receipt understanding and product-name normalization
- Artifact Registry and Cloud Build: build and store application containers
- Secret Manager: hold database credentials
- Identity Platform-ready authentication: optional managed user sign-in after the single-owner demo phase

## Completed

- [x] Created and selected the `bizlama` GCP project
- [x] Confirmed billing is enabled
- [x] Created the INR 1,000 budget alert without Pub/Sub
- [x] Opened Cloud Shell and selected project `bizlama`
- [x] Enabled the required service APIs
- [x] Created Cloud SQL instance `bizlama-db`
- [x] Selected PostgreSQL 15, Enterprise edition, `db-f1-micro`, zonal availability, Mumbai region, and 10 GB SSD
- [x] Created PostgreSQL database `bizlama`
- [x] Stored its generated password as Secret Manager secret `bizlama-db-password`
- [x] Removed the temporary password variable from Cloud Shell
- [x] Created private Standard storage bucket `bizlama-data-PROJECT_NUMBER` in Mumbai
- [x] Created dedicated application user `bizlama_app`
- [x] Created private Standard storage bucket `bizlama-receipts-PROJECT_NUMBER` in Mumbai
- [x] Enabled uniform bucket-level access and public access prevention on both buckets
- [x] Created BigQuery dataset `foodkeeper_raw` in Mumbai for unchanged source data
- [x] Created BigQuery dataset `bizlama_analytics` in Mumbai for cleaned data and analytics
- [x] Validated and uploaded the original FoodKeeper JSON to `raw/foodkeeper/2025-07-02/foodkeeper.json`
- [x] Flattened and loaded 25 categories into `foodkeeper_raw.categories`
- [x] Flattened and loaded 661 products into `foodkeeper_raw.products`
- [x] Installed Java 21 locally and verified the Spring Boot application starts with it
- [x] Created the working copy outside OneDrive
- [x] Rebuilt frontend dependencies locally with `npm ci` (539 packages, 0 reported vulnerabilities)
- [x] Verified Angular on port 4200, Spring Boot on port 8080, and the frontend API proxy
- [x] Reworked the application shell with expanded, collapsed, and mobile navigation states
- [x] Reduced the dashboard to priorities, summary metrics, quick actions, and recent activity
- [x] Moved natural-language capture to the dedicated `/activity` page
- [x] Moved expiry attention and restock guidance into the Inventory page
- [x] Verified the redesigned frontend with a production build and browser checks with no console errors
- [x] Added a persistent local database and a Cloud SQL profile with Flyway migrations
- [x] Added operational tables for catalog, product aliases, recipes, stock, orders, feedback, experiments, receipts, activities, and AI decisions
- [x] Added Indian-market branded-product normalization and the 90% confidence automation policy
- [x] Added voice entry, receipt upload/review, local/GCS storage adapters, and Vertex AI receipt extraction
- [x] Added BigQuery operational-event publishing with a local no-op fallback
- [x] Added a single-container Cloud Run build and repeatable GCP bootstrap/deploy scripts
- [x] Added app-level owner authentication, protected APIs, Secret Manager-backed cloud credentials, and optional Identity Platform token validation
- [x] Added multi-item kitchen updates and scalable recipe ingredient/step editing
- [x] Added accessible contextual tooltips to navigation and important actions
- [x] Added server-side pagination, search, filtering and aggregate summaries for inventory and orders
- [x] Added extensible kitchens, locations, users, suppliers, categories, order history and analytics-outbox schema
- [x] Added partitioned and clustered BigQuery table definitions to the repeatable bootstrap
- [x] Added a dedicated owner settings area and removed infrastructure wording from daily pages
- [x] Added and verified the BizLaMa llama logo asset
- [x] Passed backend tests, Angular production build, live browser checks, receipt confirmation, and a full persistence restart test

## Current step

Verify locally, then deploy the prepared release.

1. Copy the repository to Cloud Shell and run `infra/gcp/bootstrap.sh`, then `infra/gcp/deploy.sh`.
2. Confirm Flyway reports schema version 6 in the Cloud Run logs.
3. Run the cloud smoke-test checklist against the generated Cloud Run URL.

## Public shelf-life data decision

- Use the public USDA FSIS FoodKeeper English dataset as a fallback reference, not as the sole Indian-market authority.
- The live USDA file endpoint returned HTTP 403 from Cloud Shell, so the stored source is the archived official USDA snapshot captured on 2 July 2025; preserve this provenance.
- Keep the original dataset in Cloud Storage.
- Load the unchanged source and a reviewed, relevant subset into BigQuery for Version 1.
- Copy only reviewed, application-ready shelf-life rules into Cloud SQL.
- Give printed product expiry dates and reviewed Indian/FSSAI guidance priority over a FoodKeeper match. Add a clearly labelled mapping layer for Indian foods such as paneer and dosa batter, including source and confidence.
- Ask the owner when no trustworthy rule exists; do not invent a shelf life.
- Treat shelf-life values as guidance and allow owner overrides.

## Next steps

- [x] Finish database, user, and Secret Manager setup
- [x] Create Cloud Storage buckets for datasets and receipts
- [x] Create BigQuery raw and curated datasets
- [x] Load validated USDA FoodKeeper staging tables into BigQuery
- [ ] Create and verify the application-friendly shelf-life view (deferred until analytics work)
- [x] Prepare Cloud SQL schema and migrations
- [x] Prepare Vertex AI and Cloud Storage application adapters
- [x] Prepare BigQuery operational analytics table and publisher
- [ ] Deploy the Spring Boot API to Cloud Run
- [ ] Deploy the Angular frontend to Cloud Run (bundled with the API image)
- [ ] Run end-to-end cloud tests