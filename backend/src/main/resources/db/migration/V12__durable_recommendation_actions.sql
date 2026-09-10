-- Durable operational actions and database-enforced single active recipe truth.

ALTER TABLE recipe_versions
  ADD COLUMN active_dish_guard VARCHAR(80);

UPDATE recipe_versions
SET active_dish_guard = dish_id
WHERE active = TRUE;

ALTER TABLE recipe_versions
  ADD CONSTRAINT ck_recipe_versions_active_dish_guard
  CHECK (
    (active = TRUE AND active_dish_guard = dish_id)
    OR (active = FALSE AND active_dish_guard IS NULL)
  );

CREATE UNIQUE INDEX uq_recipe_versions_one_active_per_dish
  ON recipe_versions(active_dish_guard);

CREATE TABLE recommendation_actions (
  id VARCHAR(100) PRIMARY KEY,
  recommendation_id VARCHAR(100) NOT NULL,
  kitchen_id VARCHAR(80) NOT NULL,
  location_id VARCHAR(100) NOT NULL,
  action_type VARCHAR(40) NOT NULL,
  action_status VARCHAR(24) NOT NULL,
  ingredient_id VARCHAR(80),
  dish_id VARCHAR(80),
  recipe_version_id VARCHAR(100),
  quantity DECIMAL(24,6) NOT NULL,
  unit VARCHAR(24) NOT NULL,
  details_json TEXT NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  created_by VARCHAR(200) NOT NULL,
  CONSTRAINT uq_recommendation_actions_recommendation
    UNIQUE (recommendation_id),
  CONSTRAINT fk_recommendation_actions_recommendation
    FOREIGN KEY (recommendation_id) REFERENCES recommendations(id),
  CONSTRAINT fk_recommendation_actions_scope
    FOREIGN KEY (kitchen_id, location_id)
    REFERENCES kitchen_locations(kitchen_id, id),
  CONSTRAINT fk_recommendation_actions_ingredient
    FOREIGN KEY (kitchen_id, ingredient_id)
    REFERENCES ingredients(kitchen_id, id),
  CONSTRAINT fk_recommendation_actions_dish
    FOREIGN KEY (kitchen_id, dish_id)
    REFERENCES dishes(kitchen_id, id),
  CONSTRAINT fk_recommendation_actions_recipe
    FOREIGN KEY (dish_id, recipe_version_id)
    REFERENCES recipe_versions(dish_id, id),
  CONSTRAINT ck_recommendation_actions_type
    CHECK (action_type IN (
      'PRODUCTION_RUN',
      'PURCHASE_REQUEST',
      'UTILISATION_PLAN',
      'PURCHASE_AVOIDANCE'
    )),
  CONSTRAINT ck_recommendation_actions_status
    CHECK (action_status IN ('CREATED', 'COMPLETED')),
  CONSTRAINT ck_recommendation_actions_target
    CHECK (
      (action_type = 'PRODUCTION_RUN'
        AND ingredient_id IS NULL
        AND dish_id IS NOT NULL
        AND recipe_version_id IS NOT NULL)
      OR (action_type <> 'PRODUCTION_RUN'
        AND ingredient_id IS NOT NULL
        AND dish_id IS NULL
        AND recipe_version_id IS NULL)
    ),
  CONSTRAINT ck_recommendation_actions_quantity_positive
    CHECK (quantity > 0),
  CONSTRAINT ck_recommendation_actions_unit_present
    CHECK (CHAR_LENGTH(TRIM(unit)) > 0),
  CONSTRAINT ck_recommendation_actions_actor_present
    CHECK (CHAR_LENGTH(TRIM(created_by)) > 0)
);

CREATE TABLE recommendation_action_order_lines (
  action_id VARCHAR(100) NOT NULL,
  order_id VARCHAR(100) NOT NULL,
  line_number INTEGER NOT NULL,
  prepared_quantity INTEGER NOT NULL,
  PRIMARY KEY (action_id, order_id, line_number),
  CONSTRAINT fk_recommendation_action_lines_action
    FOREIGN KEY (action_id)
    REFERENCES recommendation_actions(id) ON DELETE CASCADE,
  CONSTRAINT fk_recommendation_action_lines_order_item
    FOREIGN KEY (order_id, line_number)
    REFERENCES order_items(order_id, line_number),
  CONSTRAINT ck_recommendation_action_lines_quantity_positive
    CHECK (prepared_quantity > 0)
);

CREATE INDEX idx_recommendation_actions_scope_time
  ON recommendation_actions(kitchen_id, location_id, created_at DESC);

CREATE INDEX idx_recommendation_action_lines_order
  ON recommendation_action_order_lines(order_id, line_number);
