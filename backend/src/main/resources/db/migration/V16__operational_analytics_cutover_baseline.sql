-- Forward-only cutover anchor for operational rows created before the
-- transactional outbox contract existed.
--
-- Inventory history cannot be reconstructed reliably from the legacy schema:
-- some old movements pre-date the outbox and a current lot balance may already
-- include movements which do have events.  Capture one immutable balance per
-- lot instead.  Analytics starts each such lot at this anchor and applies only
-- events recorded after captured_at.  That rule prevents double counting.
--
-- Orders retain their exact recipe version after V8.  Capture only ingredient
-- demand rows whose canonical outbox deduplication key is absent.  The
-- application emits these rows through the normal outbox after migration,
-- using the same deterministic unit service as live orders.  Unsupported
-- legacy units are held for explicit review rather than guessed here.

CREATE TABLE analytics_cutover_runs (
  id VARCHAR(80) PRIMARY KEY,
  baseline_schema_version INTEGER NOT NULL,
  captured_at TIMESTAMP WITH TIME ZONE NOT NULL,
  state VARCHAR(24) NOT NULL,
  inventory_lot_count BIGINT NOT NULL DEFAULT 0,
  order_demand_row_count BIGINT NOT NULL DEFAULT 0,
  outbox_ready_at TIMESTAMP WITH TIME ZONE,
  last_error VARCHAR(1000),
  CONSTRAINT ck_analytics_cutover_schema_version
    CHECK (baseline_schema_version > 0),
  CONSTRAINT ck_analytics_cutover_state
    CHECK (state IN ('PENDING', 'OUTBOX_READY', 'REVIEW_REQUIRED')),
  CONSTRAINT ck_analytics_cutover_counts
    CHECK (inventory_lot_count >= 0 AND order_demand_row_count >= 0),
  CONSTRAINT ck_analytics_cutover_completion
    CHECK (
      (state = 'OUTBOX_READY'
       AND outbox_ready_at IS NOT NULL
       AND last_error IS NULL)
      OR
      (state = 'PENDING'
       AND outbox_ready_at IS NULL
       AND last_error IS NULL)
      OR
      (state = 'REVIEW_REQUIRED'
       AND outbox_ready_at IS NULL
       AND last_error IS NOT NULL)
    )
);

INSERT INTO analytics_cutover_runs
  (id, baseline_schema_version, captured_at, state)
VALUES
  ('outbox-v16-operational-baseline', 1, CURRENT_TIMESTAMP, 'PENDING');

CREATE TABLE analytics_inventory_cutover_baseline (
  cutover_id VARCHAR(80) NOT NULL,
  lot_id VARCHAR(100) NOT NULL,
  kitchen_id VARCHAR(80) NOT NULL,
  location_id VARCHAR(100) NOT NULL,
  ingredient_id VARCHAR(80) NOT NULL,
  quantity_remaining DECIMAL(24,6) NOT NULL,
  stored_unit VARCHAR(24) NOT NULL,
  purchased_at DATE NOT NULL,
  expires_at DATE,
  expiry_provenance VARCHAR(40) NOT NULL,
  lot_status VARCHAR(24) NOT NULL,
  captured_at TIMESTAMP WITH TIME ZONE NOT NULL,
  emission_state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  emission_attempts INTEGER NOT NULL DEFAULT 0,
  outbox_event_id VARCHAR(100),
  emitted_at TIMESTAMP WITH TIME ZONE,
  review_reason_code VARCHAR(64),
  last_error VARCHAR(1000),
  PRIMARY KEY (cutover_id, lot_id),
  CONSTRAINT fk_inventory_cutover_run
    FOREIGN KEY (cutover_id) REFERENCES analytics_cutover_runs(id),
  CONSTRAINT fk_inventory_cutover_lot
    FOREIGN KEY (lot_id) REFERENCES stock_lots(id),
  CONSTRAINT fk_inventory_cutover_scope
    FOREIGN KEY (kitchen_id, location_id)
    REFERENCES kitchen_locations(kitchen_id, id),
  CONSTRAINT fk_inventory_cutover_ingredient
    FOREIGN KEY (kitchen_id, ingredient_id)
    REFERENCES ingredients(kitchen_id, id),
  CONSTRAINT fk_inventory_cutover_outbox
    FOREIGN KEY (outbox_event_id) REFERENCES analytics_outbox(id),
  CONSTRAINT ck_inventory_cutover_quantity
    CHECK (quantity_remaining >= 0),
  CONSTRAINT ck_inventory_cutover_unit
    CHECK (CHAR_LENGTH(TRIM(stored_unit)) > 0),
  CONSTRAINT ck_inventory_cutover_emission_state
    CHECK (emission_state IN ('PENDING', 'EMITTED', 'REVIEW_REQUIRED')),
  CONSTRAINT ck_inventory_cutover_attempts
    CHECK (emission_attempts >= 0),
  CONSTRAINT ck_inventory_cutover_emission_result
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

INSERT INTO analytics_inventory_cutover_baseline
  (cutover_id, lot_id, kitchen_id, location_id, ingredient_id,
   quantity_remaining, stored_unit, purchased_at, expires_at,
   expiry_provenance, lot_status, captured_at)
SELECT
  run.id,
  lot.id,
  lot.kitchen_id,
  lot.location_id,
  lot.ingredient_id,
  lot.quantity_remaining,
  lot.unit,
  lot.purchased_at,
  lot.expires_at,
  lot.expiry_provenance,
  lot.status,
  run.captured_at
FROM stock_lots lot
CROSS JOIN analytics_cutover_runs run
WHERE run.id = 'outbox-v16-operational-baseline';

CREATE TABLE analytics_order_demand_cutover_baseline (
  cutover_id VARCHAR(80) NOT NULL,
  order_id VARCHAR(100) NOT NULL,
  line_number INTEGER NOT NULL,
  ingredient_id VARCHAR(80) NOT NULL,
  kitchen_id VARCHAR(80) NOT NULL,
  location_id VARCHAR(100) NOT NULL,
  dish_id VARCHAR(80) NOT NULL,
  recipe_version_id VARCHAR(100) NOT NULL,
  ordered_quantity INTEGER NOT NULL,
  recipe_ingredient_quantity DECIMAL(24,6) NOT NULL,
  recipe_ingredient_unit VARCHAR(24) NOT NULL,
  recipe_yield_quantity DECIMAL(24,6) NOT NULL,
  recipe_yield_unit VARCHAR(24) NOT NULL,
  ingredient_name VARCHAR(160) NOT NULL,
  ingredient_base_unit VARCHAR(24) NOT NULL,
  order_occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
  required_at TIMESTAMP WITH TIME ZONE NOT NULL,
  captured_at TIMESTAMP WITH TIME ZONE NOT NULL,
  emission_state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  emission_attempts INTEGER NOT NULL DEFAULT 0,
  outbox_event_id VARCHAR(100),
  emitted_at TIMESTAMP WITH TIME ZONE,
  review_reason_code VARCHAR(64),
  last_error VARCHAR(1000),
  PRIMARY KEY (cutover_id, order_id, line_number, ingredient_id),
  CONSTRAINT fk_order_demand_cutover_run
    FOREIGN KEY (cutover_id) REFERENCES analytics_cutover_runs(id),
  CONSTRAINT fk_order_demand_cutover_order
    FOREIGN KEY (order_id) REFERENCES customer_orders(id),
  CONSTRAINT fk_order_demand_cutover_recipe
    FOREIGN KEY (recipe_version_id) REFERENCES recipe_versions(id),
  CONSTRAINT fk_order_demand_cutover_ingredient
    FOREIGN KEY (kitchen_id, ingredient_id)
    REFERENCES ingredients(kitchen_id, id),
  CONSTRAINT fk_order_demand_cutover_scope
    FOREIGN KEY (kitchen_id, location_id)
    REFERENCES kitchen_locations(kitchen_id, id),
  CONSTRAINT fk_order_demand_cutover_outbox
    FOREIGN KEY (outbox_event_id) REFERENCES analytics_outbox(id),
  CONSTRAINT ck_order_demand_cutover_quantities
    CHECK (
      line_number > 0
      AND ordered_quantity > 0
      AND recipe_ingredient_quantity > 0
      AND recipe_yield_quantity > 0
    ),
  CONSTRAINT ck_order_demand_cutover_units
    CHECK (
      CHAR_LENGTH(TRIM(recipe_ingredient_unit)) > 0
      AND CHAR_LENGTH(TRIM(recipe_yield_unit)) > 0
      AND CHAR_LENGTH(TRIM(ingredient_base_unit)) > 0
    ),
  CONSTRAINT ck_order_demand_cutover_emission_state
    CHECK (emission_state IN ('PENDING', 'EMITTED', 'REVIEW_REQUIRED')),
  CONSTRAINT ck_order_demand_cutover_attempts
    CHECK (emission_attempts >= 0),
  CONSTRAINT ck_order_demand_cutover_emission_result
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

INSERT INTO analytics_order_demand_cutover_baseline
  (cutover_id, order_id, line_number, ingredient_id, kitchen_id,
   location_id, dish_id, recipe_version_id, ordered_quantity,
   recipe_ingredient_quantity, recipe_ingredient_unit,
   recipe_yield_quantity, recipe_yield_unit, ingredient_name,
   ingredient_base_unit, order_occurred_at, required_at, captured_at)
SELECT
  run.id,
  orders.id,
  item.line_number,
  recipe_item.ingredient_id,
  orders.kitchen_id,
  orders.location_id,
  item.dish_id,
  item.recipe_version_id,
  item.quantity,
  recipe_item.quantity,
  recipe_item.unit,
  recipe.yield_quantity,
  recipe.yield_unit,
  ingredient.name,
  ingredient.base_unit,
  orders.created_at,
  orders.required_at,
  run.captured_at
FROM customer_orders orders
JOIN order_items item
  ON item.order_id = orders.id
JOIN recipe_versions recipe
  ON recipe.id = item.recipe_version_id
 AND recipe.dish_id = item.dish_id
 AND recipe.kitchen_id = orders.kitchen_id
JOIN recipe_ingredients recipe_item
  ON recipe_item.recipe_version_id = item.recipe_version_id
 AND recipe_item.kitchen_id = orders.kitchen_id
JOIN ingredients ingredient
  ON ingredient.id = recipe_item.ingredient_id
 AND ingredient.kitchen_id = orders.kitchen_id
CROSS JOIN analytics_cutover_runs run
WHERE run.id = 'outbox-v16-operational-baseline'
  AND NOT EXISTS (
    SELECT 1
    FROM analytics_outbox event
    WHERE event.kitchen_id = orders.kitchen_id
      AND COALESCE(event.deduplication_key, event.id) = CONCAT(
        'order-ingredient-demand:',
        orders.id,
        ':',
        item.line_number,
        ':',
        recipe_item.ingredient_id
      )
  );

UPDATE analytics_cutover_runs
SET inventory_lot_count = (
      SELECT COUNT(*)
      FROM analytics_inventory_cutover_baseline baseline
      WHERE baseline.cutover_id = analytics_cutover_runs.id
    ),
    order_demand_row_count = (
      SELECT COUNT(*)
      FROM analytics_order_demand_cutover_baseline baseline
      WHERE baseline.cutover_id = analytics_cutover_runs.id
    )
WHERE id = 'outbox-v16-operational-baseline';

CREATE INDEX idx_inventory_cutover_pending
  ON analytics_inventory_cutover_baseline(
    emission_state,
    cutover_id,
    kitchen_id,
    location_id,
    lot_id
  );

CREATE INDEX idx_order_demand_cutover_pending
  ON analytics_order_demand_cutover_baseline(
    emission_state,
    cutover_id,
    kitchen_id,
    location_id,
    order_id,
    line_number
  );
