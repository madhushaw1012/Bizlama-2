-- Replace `${PROJECT_ID}.${DATASET}` before applying.
CREATE TABLE IF NOT EXISTS `${PROJECT_ID}.${DATASET}.signal_raw_events` (
  event_id STRING NOT NULL, event_type STRING NOT NULL,
  schema_version INT64 NOT NULL, kitchen_id STRING NOT NULL,
  location_id STRING,
  entity_type STRING NOT NULL, entity_id STRING NOT NULL,
  occurred_at TIMESTAMP NOT NULL, recorded_at TIMESTAMP NOT NULL,
  correlation_id STRING, causation_id STRING,
  deduplication_key STRING NOT NULL,
  payload_json JSON NOT NULL, source_metadata_json JSON NOT NULL,
  outbox_state STRING NOT NULL, attempt_count INT64 NOT NULL,
  next_attempt_at TIMESTAMP, published_at TIMESTAMP, last_error STRING,
  analytics_type STRING NOT NULL, ingredient_id STRING,
  source_quantity NUMERIC, source_unit STRING,
  canonical_quantity NUMERIC, canonical_unit STRING,
  dimension STRING NOT NULL,
  snapshot_gross_demand NUMERIC, snapshot_usable_supply NUMERIC,
  snapshot_safety_stock NUMERIC, snapshot_shortage NUMERIC,
  snapshot_expiry_risk_surplus NUMERIC,
  quality_flags ARRAY<STRING>, envelope_json JSON NOT NULL
)
PARTITION BY DATE(occurred_at)
CLUSTER BY kitchen_id, entity_type, entity_id, event_type
OPTIONS (require_partition_filter = TRUE);

CREATE TABLE IF NOT EXISTS `${PROJECT_ID}.${DATASET}.signal_facts` (
  schema_version INT64 NOT NULL, event_id STRING NOT NULL,
  event_type STRING NOT NULL, kitchen_id STRING NOT NULL,
  location_id STRING NOT NULL,
  entity_type STRING NOT NULL, entity_id STRING NOT NULL,
  occurred_at TIMESTAMP NOT NULL, recorded_at TIMESTAMP NOT NULL,
  correlation_id STRING, causation_id STRING,
  deduplication_key STRING NOT NULL,
  payload_json JSON NOT NULL, source_metadata_json JSON NOT NULL,
  ingredient_id STRING NOT NULL,
  source_quantity NUMERIC, source_unit STRING,
  canonical_quantity NUMERIC, canonical_unit STRING,
  dimension STRING NOT NULL,
  snapshot_gross_demand NUMERIC, snapshot_usable_supply NUMERIC,
  snapshot_safety_stock NUMERIC, snapshot_shortage NUMERIC,
  snapshot_expiry_risk_surplus NUMERIC,
  quality_flags ARRAY<STRING>
)
PARTITION BY DATE(occurred_at)
CLUSTER BY kitchen_id, location_id, ingredient_id, event_type
OPTIONS (require_partition_filter = TRUE);

CREATE TABLE IF NOT EXISTS `${PROJECT_ID}.${DATASET}.signal_features` (
  window_name STRING NOT NULL, window_start TIMESTAMP NOT NULL,
  window_end TIMESTAMP NOT NULL, pane_index INT64 NOT NULL,
  pane_timing STRING NOT NULL, final_pane BOOL NOT NULL,
  kitchen_id STRING NOT NULL, location_id STRING NOT NULL,
  ingredient_id STRING NOT NULL,
  demand NUMERIC NOT NULL, receipts NUMERIC NOT NULL,
  consumption NUMERIC NOT NULL, waste NUMERIC NOT NULL,
  expired NUMERIC NOT NULL, reversals NUMERIC NOT NULL,
  corrections NUMERIC NOT NULL,
  snapshot_gross_demand NUMERIC, snapshot_usable_supply NUMERIC,
  snapshot_safety_stock NUMERIC, snapshot_shortage NUMERIC,
  snapshot_expiry_risk_surplus NUMERIC,
  stockouts INT64 NOT NULL, recommendation_decisions INT64 NOT NULL,
  recommendation_outcomes INT64 NOT NULL,
  data_quality_events INT64 NOT NULL, event_count INT64 NOT NULL,
  canonical_unit STRING
)
PARTITION BY DATE(window_start)
CLUSTER BY kitchen_id, location_id, ingredient_id, window_name
OPTIONS (require_partition_filter = TRUE);

CREATE TABLE IF NOT EXISTS `${PROJECT_ID}.${DATASET}.signal_pipeline_errors` (
  reason_code STRING NOT NULL, reason STRING NOT NULL,
  original_event_id STRING, event_time TIMESTAMP,
  observed_at TIMESTAMP NOT NULL, raw_json STRING
)
PARTITION BY DATE(observed_at)
CLUSTER BY reason_code, original_event_id
OPTIONS (require_partition_filter = TRUE, partition_expiration_days = 90);

-- Exact bounded replay writes only to these staging tables. The reviewed
-- reconciliation transaction replaces affected live partitions atomically.
CREATE TABLE IF NOT EXISTS `${PROJECT_ID}.${DATASET}.signal_facts_reconciled_staging` (
  schema_version INT64 NOT NULL, event_id STRING NOT NULL,
  event_type STRING NOT NULL, kitchen_id STRING NOT NULL,
  location_id STRING NOT NULL,
  entity_type STRING NOT NULL, entity_id STRING NOT NULL,
  occurred_at TIMESTAMP NOT NULL, recorded_at TIMESTAMP NOT NULL,
  correlation_id STRING, causation_id STRING,
  deduplication_key STRING NOT NULL,
  payload_json JSON NOT NULL, source_metadata_json JSON NOT NULL,
  ingredient_id STRING NOT NULL,
  source_quantity NUMERIC, source_unit STRING,
  canonical_quantity NUMERIC, canonical_unit STRING,
  dimension STRING NOT NULL,
  snapshot_gross_demand NUMERIC, snapshot_usable_supply NUMERIC,
  snapshot_safety_stock NUMERIC, snapshot_shortage NUMERIC,
  snapshot_expiry_risk_surplus NUMERIC,
  quality_flags ARRAY<STRING>
)
PARTITION BY DATE(occurred_at)
CLUSTER BY kitchen_id, location_id, ingredient_id, event_type
OPTIONS (require_partition_filter = TRUE);

CREATE TABLE IF NOT EXISTS `${PROJECT_ID}.${DATASET}.signal_features_reconciled_staging` (
  window_name STRING NOT NULL, window_start TIMESTAMP NOT NULL,
  window_end TIMESTAMP NOT NULL, pane_index INT64 NOT NULL,
  pane_timing STRING NOT NULL, final_pane BOOL NOT NULL,
  kitchen_id STRING NOT NULL, location_id STRING NOT NULL,
  ingredient_id STRING NOT NULL,
  demand NUMERIC NOT NULL, receipts NUMERIC NOT NULL,
  consumption NUMERIC NOT NULL, waste NUMERIC NOT NULL,
  expired NUMERIC NOT NULL, reversals NUMERIC NOT NULL,
  corrections NUMERIC NOT NULL,
  snapshot_gross_demand NUMERIC, snapshot_usable_supply NUMERIC,
  snapshot_safety_stock NUMERIC, snapshot_shortage NUMERIC,
  snapshot_expiry_risk_surplus NUMERIC,
  stockouts INT64 NOT NULL, recommendation_decisions INT64 NOT NULL,
  recommendation_outcomes INT64 NOT NULL,
  data_quality_events INT64 NOT NULL, event_count INT64 NOT NULL,
  canonical_unit STRING
)
PARTITION BY DATE(window_start)
CLUSTER BY kitchen_id, location_id, ingredient_id, window_name
OPTIONS (require_partition_filter = TRUE);

-- Query these views with an event/window partition predicate. They make
-- at-least-once raw delivery and accumulating feature panes explicit.
CREATE OR REPLACE VIEW `${PROJECT_ID}.${DATASET}.signal_raw_events_current` AS
SELECT * EXCEPT (delivery_rank)
FROM (
  SELECT *, ROW_NUMBER() OVER (
    PARTITION BY event_id
    ORDER BY recorded_at, deduplication_key
  ) AS delivery_rank
  FROM `${PROJECT_ID}.${DATASET}.signal_raw_events`
)
WHERE delivery_rank = 1;

CREATE OR REPLACE VIEW `${PROJECT_ID}.${DATASET}.signal_facts_current` AS
SELECT * EXCEPT (delivery_rank)
FROM (
  SELECT *, ROW_NUMBER() OVER (
    PARTITION BY event_id
    ORDER BY recorded_at, deduplication_key
  ) AS delivery_rank
  FROM `${PROJECT_ID}.${DATASET}.signal_facts`
)
WHERE delivery_rank = 1;

CREATE OR REPLACE VIEW `${PROJECT_ID}.${DATASET}.signal_features_current` AS
SELECT * EXCEPT (revision_rank)
FROM (
  SELECT *, ROW_NUMBER() OVER (
    PARTITION BY window_name, window_start, kitchen_id, location_id, ingredient_id
    ORDER BY pane_index DESC
  ) AS revision_rank
  FROM `${PROJECT_ID}.${DATASET}.signal_features`
)
WHERE revision_rank = 1;
