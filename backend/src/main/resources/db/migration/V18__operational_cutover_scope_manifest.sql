-- Forward-only, per-scope completion manifest for the V16 operational cutover.
-- A zero-row location cannot be inferred from fact absence, and partially
-- curated baseline rows must never be mistaken for a complete inventory
-- anchor. One immutable expected-count record is therefore created for every
-- location known when V18 is applied. The application emits its manifest only
-- after every staged row in that scope has reached the transactional outbox.

CREATE TABLE analytics_cutover_scope_manifests (
  cutover_id VARCHAR(80) NOT NULL,
  kitchen_id VARCHAR(80) NOT NULL,
  location_id VARCHAR(100) NOT NULL,
  captured_at TIMESTAMP WITH TIME ZONE NOT NULL,
  expected_inventory_lot_count BIGINT NOT NULL,
  expected_order_demand_row_count BIGINT NOT NULL,
  emission_state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  emission_attempts INTEGER NOT NULL DEFAULT 0,
  outbox_event_id VARCHAR(100),
  emitted_at TIMESTAMP WITH TIME ZONE,
  review_reason_code VARCHAR(64),
  last_error VARCHAR(1000),
  PRIMARY KEY (cutover_id, kitchen_id, location_id),
  CONSTRAINT fk_cutover_scope_manifest_run
    FOREIGN KEY (cutover_id) REFERENCES analytics_cutover_runs(id),
  CONSTRAINT fk_cutover_scope_manifest_location
    FOREIGN KEY (kitchen_id, location_id)
    REFERENCES kitchen_locations(kitchen_id, id),
  CONSTRAINT fk_cutover_scope_manifest_outbox
    FOREIGN KEY (outbox_event_id) REFERENCES analytics_outbox(id),
  CONSTRAINT ck_cutover_scope_manifest_counts
    CHECK (
      expected_inventory_lot_count >= 0
      AND expected_order_demand_row_count >= 0
    ),
  CONSTRAINT ck_cutover_scope_manifest_state
    CHECK (emission_state IN ('PENDING', 'EMITTED', 'REVIEW_REQUIRED')),
  CONSTRAINT ck_cutover_scope_manifest_attempts
    CHECK (emission_attempts >= 0),
  CONSTRAINT ck_cutover_scope_manifest_result
    CHECK (
      (emission_state = 'PENDING'
       AND outbox_event_id IS NULL
       AND emitted_at IS NULL
       AND review_reason_code IS NULL
       AND last_error IS NULL)
      OR
      (emission_state = 'EMITTED'
       AND outbox_event_id IS NOT NULL
       AND emitted_at IS NOT NULL
       AND review_reason_code IS NULL
       AND last_error IS NULL)
      OR
      (emission_state = 'REVIEW_REQUIRED'
       AND outbox_event_id IS NULL
       AND emitted_at IS NULL
       AND review_reason_code IS NOT NULL
       AND last_error IS NOT NULL)
    )
);

INSERT INTO analytics_cutover_scope_manifests
  (cutover_id, kitchen_id, location_id, captured_at,
   expected_inventory_lot_count, expected_order_demand_row_count)
SELECT
  run.id,
  scope_location.kitchen_id,
  scope_location.id,
  run.captured_at,
  (
    SELECT COUNT(*)
    FROM analytics_inventory_cutover_baseline inventory
    WHERE inventory.cutover_id = run.id
      AND inventory.kitchen_id = scope_location.kitchen_id
      AND inventory.location_id = scope_location.id
  ),
  (
    SELECT COUNT(*)
    FROM analytics_order_demand_cutover_baseline demand
    WHERE demand.cutover_id = run.id
      AND demand.kitchen_id = scope_location.kitchen_id
      AND demand.location_id = scope_location.id
  )
FROM analytics_cutover_runs run
CROSS JOIN kitchen_locations scope_location
WHERE run.id = 'outbox-v16-operational-baseline';

CREATE INDEX idx_cutover_scope_manifest_pending
  ON analytics_cutover_scope_manifests(
    emission_state,
    cutover_id,
    kitchen_id,
    location_id
  );
