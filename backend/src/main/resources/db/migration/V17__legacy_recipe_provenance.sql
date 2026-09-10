-- Corrective provenance for values introduced while upgrading the legacy schema.
--
-- V8 necessarily reconstructed recipe pins because the pre-V8 order schema did
-- not retain them.  Preserve those rows, but distinguish deterministic history
-- from ambiguous reconstruction so no governed action or analytical fact treats
-- an inferred pin as operational truth.

ALTER TABLE recipe_versions
  ADD COLUMN yield_provenance VARCHAR(32);

UPDATE recipe_versions
SET yield_provenance = CASE
  WHEN created_by IS NULL THEN 'LEGACY_PER_ITEM_SCHEMA'
  ELSE 'OPERATOR_ENTERED'
END
WHERE yield_provenance IS NULL;

ALTER TABLE recipe_versions
  ALTER COLUMN yield_provenance SET DEFAULT 'OPERATOR_ENTERED';

ALTER TABLE recipe_versions
  ALTER COLUMN yield_provenance SET NOT NULL;

ALTER TABLE recipe_versions
  ADD CONSTRAINT ck_recipe_versions_yield_provenance
  CHECK (yield_provenance IN (
    'LEGACY_PER_ITEM_SCHEMA',
    'OPERATOR_ENTERED',
    'OWNER_CONFIRMED'
  ));

ALTER TABLE order_items
  ADD COLUMN recipe_pin_provenance VARCHAR(32);

UPDATE order_items
SET recipe_pin_provenance = CASE
  WHEN EXISTS (
    SELECT 1
    FROM analytics_outbox event
    WHERE event.event_type = 'ORDER_INGREDIENT_DEMAND'
      AND event.correlation_id = order_items.order_id
  ) THEN 'CAPTURED_AT_ORDER'
  WHEN EXISTS (
    SELECT 1
    FROM recipe_versions pinned
    JOIN customer_orders ordered
      ON ordered.id = order_items.order_id
    WHERE pinned.id = order_items.recipe_version_id
      AND pinned.dish_id = order_items.dish_id
      AND pinned.created_at <= ordered.created_at
  )
  AND 1 = (
    SELECT COUNT(*)
    FROM recipe_versions candidate
    JOIN customer_orders ordered
      ON ordered.id = order_items.order_id
    WHERE candidate.dish_id = order_items.dish_id
      AND candidate.created_at <= ordered.created_at
  ) THEN 'LEGACY_UNAMBIGUOUS'
  ELSE 'LEGACY_RECONSTRUCTED'
END
WHERE recipe_pin_provenance IS NULL;

ALTER TABLE order_items
  ALTER COLUMN recipe_pin_provenance SET DEFAULT 'CAPTURED_AT_ORDER';

ALTER TABLE order_items
  ALTER COLUMN recipe_pin_provenance SET NOT NULL;

ALTER TABLE order_items
  ADD CONSTRAINT ck_order_items_recipe_pin_provenance
  CHECK (recipe_pin_provenance IN (
    'LEGACY_RECONSTRUCTED',
    'LEGACY_UNAMBIGUOUS',
    'CAPTURED_AT_ORDER',
    'OWNER_CONFIRMED'
  ));

CREATE TABLE legacy_provenance_reviews (
  id VARCHAR(100) PRIMARY KEY,
  review_type VARCHAR(32) NOT NULL,
  kitchen_id VARCHAR(80) NOT NULL,
  location_id VARCHAR(100) NOT NULL,
  order_id VARCHAR(100),
  line_number INTEGER,
  recipe_version_id VARCHAR(100) NOT NULL,
  previous_provenance VARCHAR(32) NOT NULL,
  resulting_provenance VARCHAR(32) NOT NULL,
  attested_quantity DECIMAL(24,6),
  attested_unit VARCHAR(24),
  reason VARCHAR(1000) NOT NULL,
  reviewed_by VARCHAR(200) NOT NULL,
  reviewed_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT fk_legacy_provenance_review_scope
    FOREIGN KEY (kitchen_id, location_id)
    REFERENCES kitchen_locations(kitchen_id, id),
  CONSTRAINT fk_legacy_provenance_review_recipe
    FOREIGN KEY (kitchen_id, recipe_version_id)
    REFERENCES recipe_versions(kitchen_id, id),
  CONSTRAINT fk_legacy_provenance_review_order_line
    FOREIGN KEY (order_id, line_number)
    REFERENCES order_items(order_id, line_number),
  CONSTRAINT ck_legacy_provenance_review_type
    CHECK (review_type IN ('RECIPE_YIELD', 'ORDER_RECIPE_PIN')),
  CONSTRAINT ck_legacy_provenance_review_result
    CHECK (resulting_provenance = 'OWNER_CONFIRMED'),
  CONSTRAINT ck_legacy_provenance_review_reason
    CHECK (
      CHAR_LENGTH(TRIM(reason)) > 0
      AND CHAR_LENGTH(TRIM(reviewed_by)) > 0
    ),
  CONSTRAINT ck_legacy_provenance_review_shape
    CHECK (
      (review_type = 'RECIPE_YIELD'
       AND order_id IS NULL
       AND line_number IS NULL
       AND attested_quantity > 0
       AND attested_unit IS NOT NULL
       AND CHAR_LENGTH(TRIM(attested_unit)) > 0)
      OR
      (review_type = 'ORDER_RECIPE_PIN'
       AND order_id IS NOT NULL
       AND line_number > 0
       AND attested_quantity IS NULL
       AND attested_unit IS NULL)
    )
);

CREATE INDEX idx_legacy_provenance_reviews_scope_time
  ON legacy_provenance_reviews(
    kitchen_id,
    location_id,
    reviewed_at DESC
  );

CREATE INDEX idx_order_items_recipe_pin_provenance
  ON order_items(recipe_pin_provenance, order_id, line_number);

-- V16 staged the operational cutover before provenance was available.  Hold
-- ambiguous order rows in its durable review state; the drain may requeue only
-- this reason after an owner attests the existing pin.
UPDATE analytics_order_demand_cutover_baseline
SET emission_state = 'REVIEW_REQUIRED',
    review_reason_code = 'LEGACY_RECIPE_PIN',
    last_error = 'Legacy recipe pin requires owner confirmation before analytical emission.'
WHERE emission_state = 'PENDING'
  AND EXISTS (
    SELECT 1
    FROM order_items item
    WHERE item.order_id = analytics_order_demand_cutover_baseline.order_id
      AND item.line_number = analytics_order_demand_cutover_baseline.line_number
      AND item.recipe_pin_provenance = 'LEGACY_RECONSTRUCTED'
  );

-- Apply yield quarantine second so a row with both defects retains the more
-- specific recipe-pin reason until that first review is resolved.
UPDATE analytics_order_demand_cutover_baseline
SET emission_state = 'REVIEW_REQUIRED',
    review_reason_code = 'LEGACY_RECIPE_YIELD',
    last_error = 'Legacy recipe yield requires owner confirmation before analytical emission.'
WHERE emission_state = 'PENDING'
  AND EXISTS (
    SELECT 1
    FROM order_items item
    JOIN recipe_versions recipe
      ON recipe.id = item.recipe_version_id
     AND recipe.kitchen_id = analytics_order_demand_cutover_baseline.kitchen_id
    WHERE item.order_id = analytics_order_demand_cutover_baseline.order_id
      AND item.line_number = analytics_order_demand_cutover_baseline.line_number
      AND recipe.yield_provenance = 'LEGACY_PER_ITEM_SCHEMA'
  );
