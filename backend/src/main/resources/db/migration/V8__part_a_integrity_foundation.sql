-- Part A corrective integrity migration.
--
-- Keep historical migrations immutable.  New required values are populated before
-- NOT NULL/check/foreign-key constraints are installed so both clean databases and
-- databases upgraded from V7 follow the same contract.

-- -----------------------------------------------------------------------------
-- Recipe truth and auditability
-- -----------------------------------------------------------------------------

ALTER TABLE recipe_versions
  ADD COLUMN yield_quantity DECIMAL(14,3);

ALTER TABLE recipe_versions
  ADD COLUMN yield_unit VARCHAR(24);

ALTER TABLE recipe_versions
  ADD COLUMN created_by VARCHAR(200);

ALTER TABLE recipe_versions
  ADD COLUMN approved_by VARCHAR(200);

ALTER TABLE recipe_versions
  ADD COLUMN approved_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE recipe_versions
  ADD COLUMN effective_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE recipe_versions
  ADD COLUMN superseded_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE recipe_versions
  ADD COLUMN superseded_by_version_id VARCHAR(100);

UPDATE recipe_versions
SET yield_quantity = 1.000,
    yield_unit = 'each'
WHERE yield_quantity IS NULL
   OR yield_unit IS NULL;

UPDATE recipe_versions
SET effective_at = created_at
WHERE active = TRUE
  AND effective_at IS NULL;

ALTER TABLE recipe_versions
  ALTER COLUMN yield_quantity SET NOT NULL;

ALTER TABLE recipe_versions
  ALTER COLUMN yield_unit SET NOT NULL;

ALTER TABLE recipe_versions
  ALTER COLUMN yield_quantity SET DEFAULT 1.000;

ALTER TABLE recipe_versions
  ALTER COLUMN yield_unit SET DEFAULT 'each';

ALTER TABLE recipe_versions
  ADD CONSTRAINT ck_recipe_versions_version_number_positive
  CHECK (version_number > 0);

ALTER TABLE recipe_versions
  ADD CONSTRAINT ck_recipe_versions_yield_quantity_positive
  CHECK (yield_quantity > 0);

ALTER TABLE recipe_versions
  ADD CONSTRAINT ck_recipe_versions_yield_unit_present
  CHECK (CHAR_LENGTH(TRIM(yield_unit)) > 0);

ALTER TABLE recipe_versions
  ADD CONSTRAINT ck_recipe_versions_active_effective
  CHECK (active = FALSE OR effective_at IS NOT NULL);

ALTER TABLE recipe_versions
  ADD CONSTRAINT ck_recipe_versions_approval_order
  CHECK (approved_at IS NULL OR effective_at IS NULL OR effective_at >= approved_at);

ALTER TABLE recipe_versions
  ADD CONSTRAINT ck_recipe_versions_supersession_order
  CHECK (superseded_at IS NULL OR effective_at IS NULL OR superseded_at >= effective_at);

ALTER TABLE recipe_versions
  ADD CONSTRAINT ck_recipe_versions_not_self_superseded
  CHECK (superseded_by_version_id IS NULL OR superseded_by_version_id <> id);

ALTER TABLE recipe_versions
  ADD CONSTRAINT uq_recipe_versions_dish_id_id
  UNIQUE (dish_id, id);

ALTER TABLE recipe_versions
  ADD CONSTRAINT fk_recipe_versions_superseded_by_same_dish
  FOREIGN KEY (dish_id, superseded_by_version_id)
  REFERENCES recipe_versions(dish_id, id);

ALTER TABLE dishes
  ADD CONSTRAINT fk_dishes_active_recipe_same_dish
  FOREIGN KEY (id, active_recipe_version_id)
  REFERENCES recipe_versions(dish_id, id);

-- An order accepted before V8 did not persist the selected version.  Prefer the
-- dish's currently active version so an unapproved proposal is never selected.
-- The time-based and earliest-version fallbacks cover damaged legacy active
-- pointers and imported orders.  This is necessarily a reconstruction because
-- pre-V8 schemas retained no recipe-activation history.
ALTER TABLE order_items
  ADD COLUMN recipe_version_id VARCHAR(100);

UPDATE order_items
SET recipe_version_id = (
  SELECT rv.id
  FROM recipe_versions rv
  WHERE rv.dish_id = order_items.dish_id
    AND rv.active = TRUE
  ORDER BY rv.version_number DESC
  LIMIT 1
)
WHERE recipe_version_id IS NULL;

UPDATE order_items
SET recipe_version_id = (
  SELECT rv.id
  FROM recipe_versions rv
  JOIN customer_orders existing_order
    ON existing_order.id = order_items.order_id
  WHERE rv.dish_id = order_items.dish_id
    AND rv.created_at <= existing_order.created_at
  ORDER BY rv.created_at DESC, rv.version_number DESC
  LIMIT 1
)
WHERE recipe_version_id IS NULL;

UPDATE order_items
SET recipe_version_id = (
  SELECT rv.id
  FROM recipe_versions rv
  WHERE rv.dish_id = order_items.dish_id
  ORDER BY rv.version_number ASC
  LIMIT 1
)
WHERE recipe_version_id IS NULL;

ALTER TABLE order_items
  ALTER COLUMN recipe_version_id SET NOT NULL;

ALTER TABLE order_items
  ADD CONSTRAINT fk_order_items_recipe_version_same_dish
  FOREIGN KEY (dish_id, recipe_version_id)
  REFERENCES recipe_versions(dish_id, id);

-- -----------------------------------------------------------------------------
-- Receipt lifecycle, evidence, and line-level review provenance
-- -----------------------------------------------------------------------------

ALTER TABLE receipt_imports
  ADD COLUMN kitchen_id VARCHAR(80);

ALTER TABLE receipt_imports
  ADD COLUMN location_id VARCHAR(100);

ALTER TABLE receipt_imports
  ADD COLUMN version INTEGER;

ALTER TABLE receipt_imports
  ADD COLUMN updated_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE receipt_imports
  ADD COLUMN failure_code VARCHAR(80);

ALTER TABLE receipt_imports
  ADD COLUMN failure_message VARCHAR(1000);

ALTER TABLE receipt_imports
  ADD COLUMN confirmation_idempotency_key VARCHAR(200);

ALTER TABLE receipt_imports
  ADD COLUMN confirmed_by VARCHAR(200);

ALTER TABLE receipt_imports
  ADD COLUMN evidence_content_type VARCHAR(120);

ALTER TABLE receipt_imports
  ADD COLUMN evidence_size_bytes BIGINT;

ALTER TABLE receipt_imports
  ADD COLUMN evidence_sha256 VARCHAR(64);

ALTER TABLE receipt_imports
  ADD COLUMN evidence_validated_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE receipt_imports
  ADD COLUMN extraction_provider VARCHAR(80);

ALTER TABLE receipt_imports
  ADD COLUMN extraction_model VARCHAR(160);

ALTER TABLE receipt_imports
  ADD COLUMN extraction_schema_version VARCHAR(40);

ALTER TABLE receipt_imports
  ADD COLUMN extracted_at TIMESTAMP WITH TIME ZONE;

UPDATE receipt_imports
SET kitchen_id = 'kitchen-default'
WHERE kitchen_id IS NULL;

UPDATE receipt_imports
SET location_id = 'location-main'
WHERE location_id IS NULL;

UPDATE receipt_imports
SET version = 1
WHERE version IS NULL;

UPDATE receipt_imports
SET updated_at = COALESCE(confirmed_at, created_at)
WHERE updated_at IS NULL;

UPDATE receipt_imports
SET status = 'REVIEW_REQUIRED'
WHERE status IN ('NEEDS_REVIEW', 'READY');

UPDATE receipt_imports
SET failure_code = 'LEGACY_FAILURE'
WHERE status = 'FAILED'
  AND failure_code IS NULL;

UPDATE receipt_imports
SET confirmed_at = created_at
WHERE status = 'CONFIRMED'
  AND confirmed_at IS NULL;

ALTER TABLE receipt_imports
  ALTER COLUMN kitchen_id SET NOT NULL;

ALTER TABLE receipt_imports
  ALTER COLUMN location_id SET NOT NULL;

ALTER TABLE receipt_imports
  ALTER COLUMN version SET NOT NULL;

ALTER TABLE receipt_imports
  ALTER COLUMN updated_at SET NOT NULL;

ALTER TABLE receipt_imports
  ALTER COLUMN version SET DEFAULT 1;

ALTER TABLE receipt_imports
  ADD CONSTRAINT ck_receipt_imports_version_positive
  CHECK (version > 0);

ALTER TABLE receipt_imports
  ADD CONSTRAINT ck_receipt_imports_status
  CHECK (status IN ('UPLOADED', 'EXTRACTING', 'REVIEW_REQUIRED', 'FAILED', 'CONFIRMED'));

ALTER TABLE receipt_imports
  ADD CONSTRAINT ck_receipt_imports_total_nonnegative
  CHECK (total IS NULL OR total >= 0);

ALTER TABLE receipt_imports
  ADD CONSTRAINT ck_receipt_imports_failure_code
  CHECK (status <> 'FAILED' OR failure_code IS NOT NULL);

ALTER TABLE receipt_imports
  ADD CONSTRAINT ck_receipt_imports_confirmation_time
  CHECK (status <> 'CONFIRMED' OR confirmed_at IS NOT NULL);

ALTER TABLE receipt_imports
  ADD CONSTRAINT ck_receipt_imports_evidence_size_positive
  CHECK (evidence_size_bytes IS NULL OR evidence_size_bytes > 0);

ALTER TABLE receipt_imports
  ADD CONSTRAINT ck_receipt_imports_evidence_sha256_length
  CHECK (evidence_sha256 IS NULL OR CHAR_LENGTH(evidence_sha256) = 64);

ALTER TABLE receipt_imports
  ADD CONSTRAINT ck_receipt_imports_updated_order
  CHECK (updated_at >= created_at);

ALTER TABLE receipt_imports
  ADD CONSTRAINT ck_receipt_imports_confirmed_order
  CHECK (confirmed_at IS NULL OR confirmed_at >= created_at);

ALTER TABLE receipt_imports
  ADD CONSTRAINT uq_receipt_imports_confirmation_idempotency
  UNIQUE (confirmation_idempotency_key);

ALTER TABLE kitchen_locations
  ADD CONSTRAINT uq_kitchen_locations_kitchen_id_id
  UNIQUE (kitchen_id, id);

ALTER TABLE ingredients
  ADD CONSTRAINT uq_ingredients_kitchen_id_id
  UNIQUE (kitchen_id, id);

ALTER TABLE receipt_imports
  ADD CONSTRAINT fk_receipt_imports_kitchen
  FOREIGN KEY (kitchen_id) REFERENCES kitchens(id);

ALTER TABLE receipt_imports
  ADD CONSTRAINT fk_receipt_imports_kitchen_location
  FOREIGN KEY (kitchen_id, location_id)
  REFERENCES kitchen_locations(kitchen_id, id);

ALTER TABLE receipt_imports
  ADD CONSTRAINT uq_receipt_imports_id_scope
  UNIQUE (id, kitchen_id, location_id);

ALTER TABLE receipt_items
  ADD COLUMN source_quantity DECIMAL(14,3);

ALTER TABLE receipt_items
  ADD COLUMN source_unit VARCHAR(24);

ALTER TABLE receipt_items
  ADD COLUMN expires_at DATE;

ALTER TABLE receipt_items
  ADD COLUMN expiry_provenance VARCHAR(40);

ALTER TABLE receipt_items
  ADD COLUMN review_status VARCHAR(24);

ALTER TABLE receipt_items
  ADD COLUMN reviewed_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE receipt_items
  ADD COLUMN reviewed_by VARCHAR(200);

UPDATE receipt_items
SET source_quantity = quantity,
    source_unit = unit,
    expiry_provenance = 'UNRESOLVED',
    review_status = 'PENDING'
WHERE source_quantity IS NULL
   OR source_unit IS NULL
   OR expiry_provenance IS NULL
   OR review_status IS NULL;

ALTER TABLE receipt_items
  ALTER COLUMN source_quantity SET NOT NULL;

ALTER TABLE receipt_items
  ALTER COLUMN source_unit SET NOT NULL;

ALTER TABLE receipt_items
  ALTER COLUMN expiry_provenance SET NOT NULL;

ALTER TABLE receipt_items
  ALTER COLUMN review_status SET NOT NULL;

ALTER TABLE receipt_items
  ALTER COLUMN expiry_provenance SET DEFAULT 'UNRESOLVED';

ALTER TABLE receipt_items
  ALTER COLUMN review_status SET DEFAULT 'PENDING';

ALTER TABLE receipt_items
  ADD CONSTRAINT ck_receipt_items_quantity_positive
  CHECK (quantity > 0);

ALTER TABLE receipt_items
  ADD CONSTRAINT ck_receipt_items_source_quantity_positive
  CHECK (source_quantity > 0);

ALTER TABLE receipt_items
  ADD CONSTRAINT ck_receipt_items_source_unit_present
  CHECK (CHAR_LENGTH(TRIM(source_unit)) > 0);

ALTER TABLE receipt_items
  ADD CONSTRAINT ck_receipt_items_unit_price_nonnegative
  CHECK (unit_price IS NULL OR unit_price >= 0);

ALTER TABLE receipt_items
  ADD CONSTRAINT ck_receipt_items_confidence_range
  CHECK (confidence >= 0 AND confidence <= 1);

ALTER TABLE receipt_items
  ADD CONSTRAINT ck_receipt_items_expiry_provenance
  CHECK (expiry_provenance IN (
    'UNRESOLVED',
    'PRINTED_DATE',
    'REVIEWED_SHELF_LIFE_RULE',
    'OWNER_CONFIRMED',
    'LEGACY_RECORDED'
  ));

ALTER TABLE receipt_items
  ADD CONSTRAINT ck_receipt_items_expiry_evidence
  CHECK (
    (expires_at IS NULL AND expiry_provenance = 'UNRESOLVED')
    OR (expires_at IS NOT NULL AND expiry_provenance <> 'UNRESOLVED')
  );

ALTER TABLE receipt_items
  ADD CONSTRAINT ck_receipt_items_review_status
  CHECK (review_status IN ('PENDING', 'APPROVED', 'REJECTED'));

ALTER TABLE receipt_items
  ADD CONSTRAINT ck_receipt_items_review_audit
  CHECK (
    (review_status = 'PENDING' AND reviewed_at IS NULL AND reviewed_by IS NULL)
    OR (review_status IN ('APPROVED', 'REJECTED')
        AND reviewed_at IS NOT NULL
        AND reviewed_by IS NOT NULL)
  );

ALTER TABLE receipt_items
  ADD CONSTRAINT fk_receipt_items_ingredient
  FOREIGN KEY (ingredient_id) REFERENCES ingredients(id);

ALTER TABLE receipt_items
  ADD CONSTRAINT uq_receipt_items_receipt_id_id
  UNIQUE (receipt_id, id);

-- -----------------------------------------------------------------------------
-- Lot provenance, state, optimistic versioning, and movement scope
-- -----------------------------------------------------------------------------

ALTER TABLE stock_lots
  ALTER COLUMN expires_at DROP NOT NULL;

ALTER TABLE stock_lots
  ADD COLUMN status VARCHAR(24);

ALTER TABLE stock_lots
  ADD COLUMN version INTEGER;

ALTER TABLE stock_lots
  ADD COLUMN expiry_provenance VARCHAR(40);

ALTER TABLE stock_lots
  ADD COLUMN source_quantity DECIMAL(14,3);

ALTER TABLE stock_lots
  ADD COLUMN source_unit VARCHAR(24);

ALTER TABLE stock_lots
  ADD COLUMN receipt_id VARCHAR(100);

ALTER TABLE stock_lots
  ADD COLUMN receipt_item_id VARCHAR(100);

UPDATE stock_lots
SET status = CASE
      WHEN quantity_remaining = 0 THEN 'DEPLETED'
      ELSE 'AVAILABLE'
    END
WHERE status IS NULL;

UPDATE stock_lots
SET version = 1
WHERE version IS NULL;

UPDATE stock_lots
SET expiry_provenance = CASE
      WHEN expires_at IS NULL THEN 'UNRESOLVED'
      ELSE 'LEGACY_RECORDED'
    END
WHERE expiry_provenance IS NULL;

UPDATE stock_lots
SET source_quantity = (
  SELECT MAX(movement.quantity_change)
  FROM stock_movements movement
  WHERE movement.stock_lot_id = stock_lots.id
    AND movement.movement_type = 'PURCHASE'
    AND movement.quantity_change > 0
),
    source_unit = unit
WHERE source_quantity IS NULL
  AND EXISTS (
    SELECT 1
    FROM stock_movements movement
    WHERE movement.stock_lot_id = stock_lots.id
      AND movement.movement_type = 'PURCHASE'
      AND movement.quantity_change > 0
  );

UPDATE stock_lots
SET source_quantity = quantity_remaining,
    source_unit = unit
WHERE source_quantity IS NULL
  AND quantity_remaining > 0;

UPDATE stock_lots
SET source_quantity = (
  SELECT SUM(CASE
      WHEN movement.quantity_change < 0 THEN -movement.quantity_change
      ELSE 0
    END)
  FROM stock_movements movement
  WHERE movement.stock_lot_id = stock_lots.id
),
    source_unit = unit
WHERE source_quantity IS NULL
  AND quantity_remaining = 0
  AND EXISTS (
    SELECT 1
    FROM stock_movements movement
    WHERE movement.stock_lot_id = stock_lots.id
      AND movement.quantity_change < 0
  );

UPDATE stock_lots
SET source_unit = NULL
WHERE source_quantity IS NULL;

ALTER TABLE stock_lots
  ALTER COLUMN status SET NOT NULL;

ALTER TABLE stock_lots
  ALTER COLUMN version SET NOT NULL;

ALTER TABLE stock_lots
  ALTER COLUMN expiry_provenance SET NOT NULL;

ALTER TABLE stock_lots
  ALTER COLUMN status SET DEFAULT 'AVAILABLE';

ALTER TABLE stock_lots
  ALTER COLUMN version SET DEFAULT 1;

ALTER TABLE stock_lots
  ADD CONSTRAINT ck_stock_lots_quantity_remaining_nonnegative
  CHECK (quantity_remaining >= 0);

ALTER TABLE stock_lots
  ADD CONSTRAINT ck_stock_lots_status
  CHECK (status IN ('AVAILABLE', 'QUARANTINED', 'DEPLETED'));

ALTER TABLE stock_lots
  ADD CONSTRAINT ck_stock_lots_version_positive
  CHECK (version > 0);

ALTER TABLE stock_lots
  ADD CONSTRAINT ck_stock_lots_purchase_expiry_order
  CHECK (expires_at IS NULL OR expires_at >= purchased_at);

ALTER TABLE stock_lots
  ADD CONSTRAINT ck_stock_lots_expiry_provenance
  CHECK (expiry_provenance IN (
    'UNRESOLVED',
    'PRINTED_DATE',
    'REVIEWED_SHELF_LIFE_RULE',
    'OWNER_CONFIRMED',
    'LEGACY_RECORDED'
  ));

ALTER TABLE stock_lots
  ADD CONSTRAINT ck_stock_lots_expiry_evidence
  CHECK (
    (expires_at IS NULL AND expiry_provenance = 'UNRESOLVED')
    OR (expires_at IS NOT NULL AND expiry_provenance <> 'UNRESOLVED')
  );

ALTER TABLE stock_lots
  ADD CONSTRAINT ck_stock_lots_source_quantity_unit
  CHECK (
    (source_quantity IS NULL AND source_unit IS NULL)
    OR (source_quantity > 0
        AND source_unit IS NOT NULL
        AND CHAR_LENGTH(TRIM(source_unit)) > 0)
  );

ALTER TABLE stock_lots
  ADD CONSTRAINT ck_stock_lots_receipt_item_requires_receipt
  CHECK (receipt_item_id IS NULL OR receipt_id IS NOT NULL);

ALTER TABLE stock_lots
  ADD CONSTRAINT fk_stock_lots_kitchen_location_scope
  FOREIGN KEY (kitchen_id, location_id)
  REFERENCES kitchen_locations(kitchen_id, id);

ALTER TABLE stock_lots
  ADD CONSTRAINT fk_stock_lots_kitchen_ingredient_scope
  FOREIGN KEY (kitchen_id, ingredient_id)
  REFERENCES ingredients(kitchen_id, id);

ALTER TABLE stock_lots
  ADD CONSTRAINT fk_stock_lots_receipt_scope
  FOREIGN KEY (receipt_id, kitchen_id, location_id)
  REFERENCES receipt_imports(id, kitchen_id, location_id);

ALTER TABLE stock_lots
  ADD CONSTRAINT fk_stock_lots_receipt_item
  FOREIGN KEY (receipt_id, receipt_item_id)
  REFERENCES receipt_items(receipt_id, id);

ALTER TABLE stock_lots
  ADD CONSTRAINT uq_stock_lots_lot_scope
  UNIQUE (id, kitchen_id, location_id, ingredient_id);

ALTER TABLE stock_movements
  ADD COLUMN kitchen_id VARCHAR(80);

ALTER TABLE stock_movements
  ADD COLUMN location_id VARCHAR(100);

UPDATE stock_movements
SET kitchen_id = (
      SELECT lot.kitchen_id
      FROM stock_lots lot
      WHERE lot.id = stock_movements.stock_lot_id
    ),
    location_id = (
      SELECT lot.location_id
      FROM stock_lots lot
      WHERE lot.id = stock_movements.stock_lot_id
    )
WHERE stock_lot_id IS NOT NULL;

UPDATE stock_movements
SET kitchen_id = (
  SELECT ingredient.kitchen_id
  FROM ingredients ingredient
  WHERE ingredient.id = stock_movements.ingredient_id
)
WHERE kitchen_id IS NULL;

UPDATE stock_movements
SET location_id = (
  SELECT MIN(location.id)
  FROM kitchen_locations location
  WHERE location.kitchen_id = stock_movements.kitchen_id
  HAVING COUNT(*) = 1
)
WHERE location_id IS NULL;

ALTER TABLE stock_movements
  ALTER COLUMN kitchen_id SET NOT NULL;

ALTER TABLE stock_movements
  ALTER COLUMN location_id SET NOT NULL;

ALTER TABLE stock_movements
  ADD CONSTRAINT ck_stock_movements_quantity_nonzero
  CHECK (quantity_change <> 0);

ALTER TABLE stock_movements
  ADD CONSTRAINT ck_stock_movements_type
  CHECK (movement_type IN (
    'PURCHASE',
    'PRODUCTION_CONSUMPTION',
    'WASTE',
    'MANUAL_ADJUSTMENT',
    'EXPIRY_WRITE_OFF',
    'REVERSAL',
    'CORRECTION'
  ));

ALTER TABLE stock_movements
  ADD CONSTRAINT ck_stock_movements_reference_present
  CHECK (
    CHAR_LENGTH(TRIM(reference_type)) > 0
    AND CHAR_LENGTH(TRIM(reference_id)) > 0
  );

ALTER TABLE stock_movements
  ADD CONSTRAINT fk_stock_movements_kitchen
  FOREIGN KEY (kitchen_id) REFERENCES kitchens(id);

ALTER TABLE stock_movements
  ADD CONSTRAINT fk_stock_movements_kitchen_location
  FOREIGN KEY (kitchen_id, location_id)
  REFERENCES kitchen_locations(kitchen_id, id);

ALTER TABLE stock_movements
  ADD CONSTRAINT fk_stock_movements_lot_scope
  FOREIGN KEY (stock_lot_id, kitchen_id, location_id, ingredient_id)
  REFERENCES stock_lots(id, kitchen_id, location_id, ingredient_id);

CREATE INDEX idx_stock_lots_usable_fefo
  ON stock_lots(kitchen_id, location_id, ingredient_id, status, expires_at);

CREATE INDEX idx_stock_movements_scope_time
  ON stock_movements(kitchen_id, location_id, occurred_at DESC);

-- -----------------------------------------------------------------------------
-- Generic experiment selection, versioning, lifecycle, and audit
-- -----------------------------------------------------------------------------

ALTER TABLE recipe_experiments
  ADD COLUMN recipe_version_id VARCHAR(100);

ALTER TABLE recipe_experiments
  ADD COLUMN version INTEGER;

ALTER TABLE recipe_experiments
  ADD COLUMN created_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE recipe_experiments
  ADD COLUMN updated_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE recipe_experiments
  ADD COLUMN created_by VARCHAR(200);

ALTER TABLE recipe_experiments
  ADD COLUMN approved_by VARCHAR(200);

ALTER TABLE recipe_experiments
  ADD COLUMN completed_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE recipe_experiments
  ADD COLUMN decision_reason VARCHAR(1000);

ALTER TABLE recipe_experiments
  ADD COLUMN superseded_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE recipe_experiments
  ADD COLUMN superseded_by_experiment_id VARCHAR(100);

UPDATE recipe_experiments
SET recipe_version_id = (
  SELECT rv.id
  FROM recipe_versions rv
  WHERE rv.dish_id = recipe_experiments.dish_id
    AND rv.active = TRUE
  ORDER BY rv.version_number DESC
  LIMIT 1
)
WHERE recipe_version_id IS NULL;

UPDATE recipe_experiments
SET recipe_version_id = (
  SELECT rv.id
  FROM recipe_versions rv
  WHERE rv.dish_id = recipe_experiments.dish_id
  ORDER BY rv.version_number DESC
  LIMIT 1
)
WHERE recipe_version_id IS NULL;

UPDATE recipe_experiments
SET version = 1
WHERE version IS NULL;

UPDATE recipe_experiments
SET created_at = COALESCE(approved_at, CURRENT_TIMESTAMP)
WHERE created_at IS NULL;

UPDATE recipe_experiments
SET updated_at = COALESCE(approved_at, created_at)
WHERE updated_at IS NULL;

UPDATE recipe_experiments
SET approved_at = created_at
WHERE status IN ('APPROVED', 'ACTIVE', 'COMPLETED')
  AND approved_at IS NULL;

ALTER TABLE recipe_experiments
  ALTER COLUMN recipe_version_id SET NOT NULL;

ALTER TABLE recipe_experiments
  ALTER COLUMN version SET NOT NULL;

ALTER TABLE recipe_experiments
  ALTER COLUMN created_at SET NOT NULL;

ALTER TABLE recipe_experiments
  ALTER COLUMN updated_at SET NOT NULL;

ALTER TABLE recipe_experiments
  ALTER COLUMN version SET DEFAULT 1;

ALTER TABLE recipe_experiments
  ALTER COLUMN created_at SET DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE recipe_experiments
  ALTER COLUMN updated_at SET DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE recipe_experiments
  ADD CONSTRAINT ck_recipe_experiments_version_positive
  CHECK (version > 0);

ALTER TABLE recipe_experiments
  ADD CONSTRAINT ck_recipe_experiments_counts_nonnegative
  CHECK (theme_count >= 0 AND feedback_count >= 0);

ALTER TABLE recipe_experiments
  ADD CONSTRAINT ck_recipe_experiments_duration_positive
  CHECK (test_duration_days > 0);

ALTER TABLE recipe_experiments
  ADD CONSTRAINT ck_recipe_experiments_status
  CHECK (status IN (
    'PROPOSED',
    'APPROVED',
    'ACTIVE',
    'COMPLETED',
    'REJECTED',
    'CANCELLED',
    'SUPERSEDED'
  ));

ALTER TABLE recipe_experiments
  ADD CONSTRAINT ck_recipe_experiments_approval_state
  CHECK (status NOT IN ('APPROVED', 'ACTIVE', 'COMPLETED') OR approved_at IS NOT NULL);

ALTER TABLE recipe_experiments
  ADD CONSTRAINT ck_recipe_experiments_updated_order
  CHECK (updated_at >= created_at);

ALTER TABLE recipe_experiments
  ADD CONSTRAINT ck_recipe_experiments_completion_order
  CHECK (completed_at IS NULL OR completed_at >= created_at);

ALTER TABLE recipe_experiments
  ADD CONSTRAINT ck_recipe_experiments_supersession_order
  CHECK (superseded_at IS NULL OR superseded_at >= created_at);

ALTER TABLE recipe_experiments
  ADD CONSTRAINT ck_recipe_experiments_not_self_superseded
  CHECK (
    superseded_by_experiment_id IS NULL
    OR superseded_by_experiment_id <> id
  );

ALTER TABLE recipe_experiments
  ADD CONSTRAINT fk_recipe_experiments_recipe_same_dish
  FOREIGN KEY (dish_id, recipe_version_id)
  REFERENCES recipe_versions(dish_id, id);

ALTER TABLE recipe_experiments
  ADD CONSTRAINT uq_recipe_experiments_dish_id_id
  UNIQUE (dish_id, id);

ALTER TABLE recipe_experiments
  ADD CONSTRAINT fk_recipe_experiments_superseded_by_same_dish
  FOREIGN KEY (dish_id, superseded_by_experiment_id)
  REFERENCES recipe_experiments(dish_id, id);

-- -----------------------------------------------------------------------------
-- Existing-column checks and missing historical references
-- -----------------------------------------------------------------------------

ALTER TABLE recipe_ingredients
  ADD CONSTRAINT ck_recipe_ingredients_quantity_positive
  CHECK (quantity > 0);

ALTER TABLE recipe_steps
  ADD CONSTRAINT ck_recipe_steps_step_number_positive
  CHECK (step_number > 0);

ALTER TABLE dishes
  ADD CONSTRAINT ck_dishes_price_positive
  CHECK (price > 0);

ALTER TABLE dishes
  ADD CONSTRAINT ck_dishes_preparation_minutes_nonnegative
  CHECK (preparation_minutes IS NULL OR preparation_minutes >= 0);

ALTER TABLE stock_lots
  ADD CONSTRAINT ck_stock_lots_unit_cost_nonnegative
  CHECK (unit_cost IS NULL OR unit_cost >= 0);

ALTER TABLE customer_orders
  ADD CONSTRAINT ck_customer_orders_total_nonnegative
  CHECK (total >= 0);

ALTER TABLE customer_orders
  ADD CONSTRAINT ck_customer_orders_status
  CHECK (status IN ('QUEUED', 'PREPARING', 'READY', 'COMPLETED', 'CANCELLED'));

ALTER TABLE customer_orders
  ADD CONSTRAINT ck_customer_orders_payment_status
  CHECK (payment_status IN (
    'PENDING',
    'PAID',
    'PARTIALLY_PAID',
    'FAILED',
    'PARTIALLY_REFUNDED',
    'REFUNDED',
    'VOID'
  ));

ALTER TABLE customer_orders
  ADD CONSTRAINT fk_customer_orders_kitchen_location_scope
  FOREIGN KEY (kitchen_id, location_id)
  REFERENCES kitchen_locations(kitchen_id, id);

ALTER TABLE order_status_history
  ADD CONSTRAINT ck_order_status_history_status
  CHECK (status IN ('QUEUED', 'PREPARING', 'READY', 'COMPLETED', 'CANCELLED'));

ALTER TABLE order_items
  ADD CONSTRAINT ck_order_items_line_number_positive
  CHECK (line_number > 0);

ALTER TABLE order_items
  ADD CONSTRAINT ck_order_items_quantity_positive
  CHECK (quantity > 0);

ALTER TABLE order_items
  ADD CONSTRAINT ck_order_items_unit_price_positive
  CHECK (unit_price > 0);

ALTER TABLE feedback
  ADD CONSTRAINT ck_feedback_rating_range
  CHECK (rating >= 1 AND rating <= 5);

ALTER TABLE feedback
  ADD CONSTRAINT fk_feedback_recipe_version
  FOREIGN KEY (recipe_id) REFERENCES recipe_versions(id);

ALTER TABLE ingredient_aliases
  ADD CONSTRAINT ck_ingredient_aliases_confidence_range
  CHECK (confidence >= 0 AND confidence <= 1);

ALTER TABLE ai_actions
  ADD CONSTRAINT ck_ai_actions_confidence_range
  CHECK (confidence >= 0 AND confidence <= 1);

ALTER TABLE unit_conversions
  ADD CONSTRAINT ck_unit_conversions_multiplier_positive
  CHECK (multiplier > 0);

ALTER TABLE ingredients
  ADD CONSTRAINT ck_ingredients_reorder_point_nonnegative
  CHECK (reorder_point IS NULL OR reorder_point >= 0);

ALTER TABLE analytics_outbox
  ADD CONSTRAINT ck_analytics_outbox_attempt_count_nonnegative
  CHECK (attempt_count >= 0);

-- -----------------------------------------------------------------------------
-- Server-side kitchen-event proposals prevent confirmation of a client-mutated
-- parse result.  The JSON payload is persisted as immutable evidence; application
-- code must reload it by id/version and revalidate it before applying any action.
-- -----------------------------------------------------------------------------

CREATE TABLE kitchen_event_proposals (
  id VARCHAR(100) PRIMARY KEY,
  kitchen_id VARCHAR(80) NOT NULL,
  version INTEGER NOT NULL DEFAULT 1,
  original_input VARCHAR(2000) NOT NULL,
  events_json TEXT NOT NULL,
  risk_tier VARCHAR(16) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  applied_at TIMESTAMP WITH TIME ZONE,
  actor VARCHAR(200),
  confirmation_idempotency_key VARCHAR(200),
  CONSTRAINT fk_kitchen_event_proposals_kitchen
    FOREIGN KEY (kitchen_id) REFERENCES kitchens(id),
  CONSTRAINT ck_kitchen_event_proposals_version_positive
    CHECK (version > 0),
  CONSTRAINT ck_kitchen_event_proposals_risk_tier
    CHECK (risk_tier IN ('LOW', 'MEDIUM', 'HIGH')),
  CONSTRAINT ck_kitchen_event_proposals_status
    CHECK (status IN ('PENDING', 'APPLIED', 'EXPIRED', 'SUPERSEDED')),
  CONSTRAINT ck_kitchen_event_proposals_expiry_order
    CHECK (expires_at > created_at),
  CONSTRAINT ck_kitchen_event_proposals_applied_state
    CHECK (
      (status = 'APPLIED'
        AND applied_at IS NOT NULL
        AND actor IS NOT NULL
        AND confirmation_idempotency_key IS NOT NULL)
      OR (status <> 'APPLIED' AND applied_at IS NULL)
    ),
  CONSTRAINT ck_kitchen_event_proposals_applied_order
    CHECK (applied_at IS NULL OR applied_at >= created_at),
  CONSTRAINT uq_kitchen_event_proposals_confirmation_idempotency
    UNIQUE (confirmation_idempotency_key)
);

CREATE INDEX idx_kitchen_event_proposals_pending
  ON kitchen_event_proposals(kitchen_id, status, expires_at);
