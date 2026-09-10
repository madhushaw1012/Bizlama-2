-- Deterministic demand calculations and governed operational recommendations.

ALTER TABLE dishes
  ADD CONSTRAINT uq_dishes_kitchen_id_id
  UNIQUE (kitchen_id, id);

ALTER TABLE customer_orders
  ADD COLUMN required_at TIMESTAMP WITH TIME ZONE;

UPDATE customer_orders
SET required_at = created_at
WHERE required_at IS NULL;

ALTER TABLE customer_orders
  ALTER COLUMN required_at SET NOT NULL;

ALTER TABLE customer_orders
  ALTER COLUMN required_at SET DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE customer_orders
  ADD CONSTRAINT ck_customer_orders_required_order
  CHECK (required_at >= created_at);

ALTER TABLE order_items
  ADD COLUMN prepared_quantity INTEGER DEFAULT 0 NOT NULL;

ALTER TABLE order_items
  ADD CONSTRAINT ck_order_items_prepared_quantity
  CHECK (prepared_quantity >= 0 AND prepared_quantity <= quantity);

CREATE TABLE ingredient_safety_stock (
  kitchen_id VARCHAR(80) NOT NULL,
  location_id VARCHAR(100) NOT NULL,
  ingredient_id VARCHAR(80) NOT NULL,
  quantity DECIMAL(24,6) NOT NULL,
  unit VARCHAR(24) NOT NULL,
  version INTEGER NOT NULL DEFAULT 1,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_by VARCHAR(200) NOT NULL,
  PRIMARY KEY (kitchen_id, location_id, ingredient_id),
  CONSTRAINT fk_safety_stock_kitchen_location
    FOREIGN KEY (kitchen_id, location_id)
    REFERENCES kitchen_locations(kitchen_id, id),
  CONSTRAINT fk_safety_stock_kitchen_ingredient
    FOREIGN KEY (kitchen_id, ingredient_id)
    REFERENCES ingredients(kitchen_id, id),
  CONSTRAINT ck_safety_stock_quantity_nonnegative
    CHECK (quantity >= 0),
  CONSTRAINT ck_safety_stock_unit_present
    CHECK (CHAR_LENGTH(TRIM(unit)) > 0),
  CONSTRAINT ck_safety_stock_version_positive
    CHECK (version > 0)
);

CREATE TABLE demand_calculation_snapshots (
  id VARCHAR(100) PRIMARY KEY,
  kitchen_id VARCHAR(80) NOT NULL,
  location_id VARCHAR(100) NOT NULL,
  horizon_start TIMESTAMP WITH TIME ZONE NOT NULL,
  horizon_end TIMESTAMP WITH TIME ZONE NOT NULL,
  as_of TIMESTAMP WITH TIME ZONE NOT NULL,
  schema_version INTEGER NOT NULL DEFAULT 1,
  calculation_method VARCHAR(80) NOT NULL,
  payload_json TEXT NOT NULL,
  payload_sha256 VARCHAR(64) NOT NULL,
  calculated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  created_by VARCHAR(200) NOT NULL,
  CONSTRAINT fk_demand_snapshots_kitchen_location
    FOREIGN KEY (kitchen_id, location_id)
    REFERENCES kitchen_locations(kitchen_id, id),
  CONSTRAINT ck_demand_snapshots_horizon
    CHECK (horizon_start < horizon_end),
  CONSTRAINT ck_demand_snapshots_as_of
    CHECK (as_of >= horizon_start AND as_of < horizon_end),
  CONSTRAINT ck_demand_snapshots_schema_version_positive
    CHECK (schema_version > 0),
  CONSTRAINT ck_demand_snapshots_payload_sha256_length
    CHECK (CHAR_LENGTH(payload_sha256) = 64),
  CONSTRAINT uq_demand_snapshots_id_scope
    UNIQUE (id, kitchen_id, location_id)
);

CREATE TABLE recommendations (
  id VARCHAR(100) PRIMARY KEY,
  kitchen_id VARCHAR(80) NOT NULL,
  location_id VARCHAR(100) NOT NULL,
  recommendation_type VARCHAR(40) NOT NULL,
  ingredient_id VARCHAR(80),
  dish_id VARCHAR(80),
  recipe_version_id VARCHAR(100),
  proposed_quantity DECIMAL(24,6) NOT NULL,
  unit VARCHAR(24) NOT NULL,
  calculation_id VARCHAR(100) NOT NULL,
  calculation_schema_version INTEGER NOT NULL DEFAULT 1,
  confidence DECIMAL(5,4) NOT NULL,
  confidence_components_json TEXT NOT NULL,
  reason_code VARCHAR(80) NOT NULL,
  risk_tier VARCHAR(16) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  version INTEGER NOT NULL DEFAULT 1,
  expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by VARCHAR(200) NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  decided_at TIMESTAMP WITH TIME ZONE,
  decided_by VARCHAR(200),
  decision_reason VARCHAR(1000),
  applied_at TIMESTAMP WITH TIME ZONE,
  applied_by VARCHAR(200),
  applied_action_type VARCHAR(80),
  applied_action_id VARCHAR(120),
  supersedes_recommendation_id VARCHAR(100),
  superseded_by_recommendation_id VARCHAR(100),
  reversed_at TIMESTAMP WITH TIME ZONE,
  reversed_by VARCHAR(200),
  reversal_reason VARCHAR(1000),
  CONSTRAINT fk_recommendations_kitchen_location
    FOREIGN KEY (kitchen_id, location_id)
    REFERENCES kitchen_locations(kitchen_id, id),
  CONSTRAINT fk_recommendations_kitchen_ingredient
    FOREIGN KEY (kitchen_id, ingredient_id)
    REFERENCES ingredients(kitchen_id, id),
  CONSTRAINT fk_recommendations_kitchen_dish
    FOREIGN KEY (kitchen_id, dish_id)
    REFERENCES dishes(kitchen_id, id),
  CONSTRAINT fk_recommendations_dish_recipe
    FOREIGN KEY (dish_id, recipe_version_id)
    REFERENCES recipe_versions(dish_id, id),
  CONSTRAINT fk_recommendations_calculation_scope
    FOREIGN KEY (calculation_id, kitchen_id, location_id)
    REFERENCES demand_calculation_snapshots(id, kitchen_id, location_id),
  CONSTRAINT ck_recommendations_type
    CHECK (recommendation_type IN (
      'PURCHASE',
      'PREPARE',
      'UTILISE_EXPIRING_STOCK',
      'REDUCE_OR_AVOID_PURCHASE'
    )),
  CONSTRAINT ck_recommendations_target
    CHECK (
      (recommendation_type = 'PREPARE'
        AND ingredient_id IS NULL
        AND dish_id IS NOT NULL
        AND recipe_version_id IS NOT NULL)
      OR (recommendation_type <> 'PREPARE'
        AND ingredient_id IS NOT NULL
        AND dish_id IS NULL
        AND recipe_version_id IS NULL)
    ),
  CONSTRAINT ck_recommendations_quantity_positive
    CHECK (proposed_quantity > 0),
  CONSTRAINT ck_recommendations_unit_present
    CHECK (CHAR_LENGTH(TRIM(unit)) > 0),
  CONSTRAINT ck_recommendations_calculation_version_positive
    CHECK (calculation_schema_version > 0),
  CONSTRAINT ck_recommendations_confidence_range
    CHECK (confidence >= 0 AND confidence <= 1),
  CONSTRAINT ck_recommendations_risk_tier
    CHECK (risk_tier IN ('LOW', 'MEDIUM', 'HIGH')),
  CONSTRAINT ck_recommendations_status
    CHECK (status IN (
      'PENDING',
      'APPROVED',
      'DISMISSED',
      'INVENTORY_FLAGGED',
      'APPLIED',
      'EXPIRED',
      'SUPERSEDED',
      'REVERSED'
    )),
  CONSTRAINT ck_recommendations_version_positive
    CHECK (version > 0),
  CONSTRAINT ck_recommendations_expiry_order
    CHECK (expires_at > created_at),
  CONSTRAINT ck_recommendations_updated_order
    CHECK (updated_at >= created_at),
  CONSTRAINT ck_recommendations_decision_audit
    CHECK (
      status = 'PENDING'
      OR (decided_at IS NOT NULL AND decided_by IS NOT NULL)
    ),
  CONSTRAINT ck_recommendations_applied_audit
    CHECK (
      status <> 'APPLIED'
      OR (applied_at IS NOT NULL
        AND applied_by IS NOT NULL
        AND applied_action_type IS NOT NULL
        AND applied_action_id IS NOT NULL)
    ),
  CONSTRAINT ck_recommendations_superseded_audit
    CHECK (status <> 'SUPERSEDED' OR decision_reason IS NOT NULL),
  CONSTRAINT ck_recommendations_reversal_audit
    CHECK (
      status <> 'REVERSED'
      OR (reversed_at IS NOT NULL
        AND reversed_by IS NOT NULL
        AND reversal_reason IS NOT NULL)
    )
);

ALTER TABLE recommendations
  ADD CONSTRAINT fk_recommendations_supersedes
  FOREIGN KEY (supersedes_recommendation_id)
  REFERENCES recommendations(id);

ALTER TABLE recommendations
  ADD CONSTRAINT fk_recommendations_superseded_by
  FOREIGN KEY (superseded_by_recommendation_id)
  REFERENCES recommendations(id);

CREATE TABLE recommendation_decisions (
  id VARCHAR(100) PRIMARY KEY,
  recommendation_id VARCHAR(100) NOT NULL,
  recommendation_version INTEGER NOT NULL,
  decision_type VARCHAR(32) NOT NULL,
  previous_quantity DECIMAL(24,6),
  new_quantity DECIMAL(24,6),
  actor VARCHAR(200) NOT NULL,
  reason VARCHAR(1000) NOT NULL,
  details_json TEXT,
  decided_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_recommendation_decisions_recommendation
    FOREIGN KEY (recommendation_id) REFERENCES recommendations(id),
  CONSTRAINT ck_recommendation_decisions_version_positive
    CHECK (recommendation_version > 0),
  CONSTRAINT ck_recommendation_decisions_type
    CHECK (decision_type IN (
      'CREATED',
      'APPROVED',
      'EDITED',
      'DISMISSED',
      'INVENTORY_FLAGGED',
      'APPLIED',
      'OUTCOME_RECORDED',
      'SUPERSEDED',
      'REVERSED',
      'EXPIRED'
    )),
  CONSTRAINT ck_recommendation_decisions_quantities
    CHECK (
      (previous_quantity IS NULL OR previous_quantity > 0)
      AND (new_quantity IS NULL OR new_quantity > 0)
    )
);

CREATE TABLE recommendation_outcomes (
  id VARCHAR(100) PRIMARY KEY,
  recommendation_id VARCHAR(100) NOT NULL,
  outcome_type VARCHAR(40) NOT NULL,
  quantity DECIMAL(24,6),
  unit VARCHAR(24),
  source_reference_type VARCHAR(80),
  source_reference_id VARCHAR(120),
  notes VARCHAR(1000),
  occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
  recorded_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  recorded_by VARCHAR(200) NOT NULL,
  CONSTRAINT fk_recommendation_outcomes_recommendation
    FOREIGN KEY (recommendation_id) REFERENCES recommendations(id),
  CONSTRAINT ck_recommendation_outcomes_type
    CHECK (outcome_type IN (
      'PREPARED',
      'SOLD',
      'FULFILLED',
      'WASTED',
      'EMERGENCY_PURCHASED',
      'STOCKOUT',
      'OVERRIDE',
      'CORRECTED_INVENTORY'
    )),
  CONSTRAINT ck_recommendation_outcomes_quantity_unit
    CHECK (
      (quantity IS NULL AND unit IS NULL)
      OR (quantity > 0 AND unit IS NOT NULL AND CHAR_LENGTH(TRIM(unit)) > 0)
    ),
  CONSTRAINT ck_recommendation_outcomes_source_reference
    CHECK (
      (source_reference_type IS NULL AND source_reference_id IS NULL)
      OR (source_reference_type IS NOT NULL AND source_reference_id IS NOT NULL)
    ),
  CONSTRAINT ck_recommendation_outcomes_recorded_order
    CHECK (recorded_at >= occurred_at)
);

CREATE INDEX idx_safety_stock_scope
  ON ingredient_safety_stock(kitchen_id, location_id, ingredient_id);

CREATE INDEX idx_orders_scope_required
  ON customer_orders(kitchen_id, location_id, status, required_at);

CREATE INDEX idx_demand_snapshots_scope_time
  ON demand_calculation_snapshots(kitchen_id, location_id, calculated_at DESC);

CREATE INDEX idx_recommendations_attention
  ON recommendations(kitchen_id, location_id, status, expires_at, risk_tier);

CREATE INDEX idx_recommendations_ingredient
  ON recommendations(kitchen_id, location_id, ingredient_id, created_at DESC);

CREATE INDEX idx_recommendation_decisions_history
  ON recommendation_decisions(recommendation_id, decided_at);

CREATE INDEX idx_recommendation_outcomes_history
  ON recommendation_outcomes(recommendation_id, occurred_at);
