-- Make tenant ownership durable for records whose scope was historically
-- implicit, then add composite foreign keys that prevent cross-kitchen links.

ALTER TABLE catalog_categories
  ADD CONSTRAINT uq_catalog_categories_kitchen_id_id
  UNIQUE (kitchen_id, id);

UPDATE catalog_categories
SET parent_id = NULL
WHERE parent_id IS NOT NULL
  AND NOT EXISTS (
    SELECT 1
    FROM catalog_categories parent
    WHERE parent.id = catalog_categories.parent_id
      AND parent.kitchen_id = catalog_categories.kitchen_id
  );

ALTER TABLE catalog_categories
  ADD CONSTRAINT fk_catalog_categories_parent_same_kitchen
  FOREIGN KEY (kitchen_id, parent_id)
  REFERENCES catalog_categories(kitchen_id, id);

UPDATE ingredients
SET category_id = NULL
WHERE category_id IS NOT NULL
  AND NOT EXISTS (
    SELECT 1
    FROM catalog_categories category
    WHERE category.id = ingredients.category_id
      AND category.kitchen_id = ingredients.kitchen_id
  );

ALTER TABLE ingredients
  ADD CONSTRAINT fk_ingredients_category_same_kitchen
  FOREIGN KEY (kitchen_id, category_id)
  REFERENCES catalog_categories(kitchen_id, id);

UPDATE dishes
SET category_id = NULL
WHERE category_id IS NOT NULL
  AND NOT EXISTS (
    SELECT 1
    FROM catalog_categories category
    WHERE category.id = dishes.category_id
      AND category.kitchen_id = dishes.kitchen_id
  );

ALTER TABLE dishes
  ADD CONSTRAINT fk_dishes_category_same_kitchen
  FOREIGN KEY (kitchen_id, category_id)
  REFERENCES catalog_categories(kitchen_id, id);

-- Recipe children previously inherited ownership only through dish_id.  The
-- copied key makes reads straightforward and prevents a recipe from pointing
-- at a dish or ingredient in another kitchen.
ALTER TABLE recipe_versions
  ADD COLUMN kitchen_id VARCHAR(80);

UPDATE recipe_versions
SET kitchen_id = (
  SELECT dish.kitchen_id
  FROM dishes dish
  WHERE dish.id = recipe_versions.dish_id
)
WHERE kitchen_id IS NULL;

ALTER TABLE recipe_versions
  ALTER COLUMN kitchen_id SET NOT NULL;

ALTER TABLE recipe_versions
  ADD CONSTRAINT fk_recipe_versions_kitchen
  FOREIGN KEY (kitchen_id) REFERENCES kitchens(id);

ALTER TABLE recipe_versions
  ADD CONSTRAINT fk_recipe_versions_dish_same_kitchen
  FOREIGN KEY (kitchen_id, dish_id)
  REFERENCES dishes(kitchen_id, id);

ALTER TABLE recipe_versions
  ADD CONSTRAINT uq_recipe_versions_kitchen_id_id
  UNIQUE (kitchen_id, id);

ALTER TABLE recipe_ingredients
  ADD COLUMN kitchen_id VARCHAR(80);

UPDATE recipe_ingredients
SET kitchen_id = (
  SELECT recipe.kitchen_id
  FROM recipe_versions recipe
  WHERE recipe.id = recipe_ingredients.recipe_version_id
)
WHERE kitchen_id IS NULL;

ALTER TABLE recipe_ingredients
  ALTER COLUMN kitchen_id SET NOT NULL;

ALTER TABLE recipe_ingredients
  ADD CONSTRAINT fk_recipe_ingredients_recipe_same_kitchen
  FOREIGN KEY (kitchen_id, recipe_version_id)
  REFERENCES recipe_versions(kitchen_id, id);

ALTER TABLE recipe_ingredients
  ADD CONSTRAINT fk_recipe_ingredients_ingredient_same_kitchen
  FOREIGN KEY (kitchen_id, ingredient_id)
  REFERENCES ingredients(kitchen_id, id);

ALTER TABLE recipe_steps
  ADD COLUMN kitchen_id VARCHAR(80);

UPDATE recipe_steps
SET kitchen_id = (
  SELECT recipe.kitchen_id
  FROM recipe_versions recipe
  WHERE recipe.id = recipe_steps.recipe_version_id
)
WHERE kitchen_id IS NULL;

ALTER TABLE recipe_steps
  ALTER COLUMN kitchen_id SET NOT NULL;

ALTER TABLE recipe_steps
  ADD CONSTRAINT fk_recipe_steps_recipe_same_kitchen
  FOREIGN KEY (kitchen_id, recipe_version_id)
  REFERENCES recipe_versions(kitchen_id, id);

-- Feedback and experiments are kitchen-wide recipe resources (there is no
-- location dimension in their domain model).
ALTER TABLE feedback
  ADD COLUMN kitchen_id VARCHAR(80);

UPDATE feedback
SET kitchen_id = (
  SELECT recipe.kitchen_id
  FROM recipe_versions recipe
  WHERE recipe.id = feedback.recipe_id
)
WHERE kitchen_id IS NULL;

ALTER TABLE feedback
  ALTER COLUMN kitchen_id SET NOT NULL;

ALTER TABLE feedback
  ADD CONSTRAINT fk_feedback_recipe_same_kitchen
  FOREIGN KEY (kitchen_id, recipe_id)
  REFERENCES recipe_versions(kitchen_id, id);

CREATE INDEX idx_feedback_kitchen_recipe_time
  ON feedback(kitchen_id, recipe_id, occurred_at DESC);

ALTER TABLE recipe_experiments
  ADD COLUMN kitchen_id VARCHAR(80);

UPDATE recipe_experiments
SET kitchen_id = (
  SELECT dish.kitchen_id
  FROM dishes dish
  WHERE dish.id = recipe_experiments.dish_id
)
WHERE kitchen_id IS NULL;

ALTER TABLE recipe_experiments
  ALTER COLUMN kitchen_id SET NOT NULL;

ALTER TABLE recipe_experiments
  ADD CONSTRAINT fk_recipe_experiments_dish_same_kitchen
  FOREIGN KEY (kitchen_id, dish_id)
  REFERENCES dishes(kitchen_id, id);

ALTER TABLE recipe_experiments
  ADD CONSTRAINT fk_recipe_experiments_recipe_scope
  FOREIGN KEY (kitchen_id, recipe_version_id)
  REFERENCES recipe_versions(kitchen_id, id);

CREATE INDEX idx_recipe_experiments_kitchen_dish
  ON recipe_experiments(kitchen_id, dish_id, status, updated_at DESC);

-- Catalog aliases and conversions belong to the same kitchen as their
-- ingredient.  Keeping the copied key also lets every lookup bind scope at
-- the first SQL boundary.
ALTER TABLE ingredient_aliases
  ADD COLUMN kitchen_id VARCHAR(80);

UPDATE ingredient_aliases
SET kitchen_id = (
  SELECT ingredient.kitchen_id
  FROM ingredients ingredient
  WHERE ingredient.id = ingredient_aliases.ingredient_id
)
WHERE kitchen_id IS NULL;

ALTER TABLE ingredient_aliases
  ALTER COLUMN kitchen_id SET NOT NULL;

ALTER TABLE ingredient_aliases
  ADD CONSTRAINT fk_ingredient_aliases_ingredient_same_kitchen
  FOREIGN KEY (kitchen_id, ingredient_id)
  REFERENCES ingredients(kitchen_id, id);

CREATE INDEX idx_ingredient_aliases_kitchen_alias
  ON ingredient_aliases(kitchen_id, alias_normalized);

ALTER TABLE unit_conversions
  ADD COLUMN kitchen_id VARCHAR(80);

UPDATE unit_conversions
SET kitchen_id = (
  SELECT ingredient.kitchen_id
  FROM ingredients ingredient
  WHERE ingredient.id = unit_conversions.ingredient_id
)
WHERE kitchen_id IS NULL;

ALTER TABLE unit_conversions
  ALTER COLUMN kitchen_id SET NOT NULL;

ALTER TABLE unit_conversions
  ADD CONSTRAINT fk_unit_conversions_ingredient_same_kitchen
  FOREIGN KEY (kitchen_id, ingredient_id)
  REFERENCES ingredients(kitchen_id, id);

-- Shelf-life rules already carried kitchen_id, but their original ingredient
-- foreign key was ID-only.  The ingredient is globally unique, so repair any
-- mismatched copied key from that authoritative parent before enforcing it.
UPDATE shelf_life_rules
SET kitchen_id = (
  SELECT ingredient.kitchen_id
  FROM ingredients ingredient
  WHERE ingredient.id = shelf_life_rules.ingredient_id
)
WHERE NOT EXISTS (
  SELECT 1
  FROM ingredients ingredient
  WHERE ingredient.id = shelf_life_rules.ingredient_id
    AND ingredient.kitchen_id = shelf_life_rules.kitchen_id
);

ALTER TABLE shelf_life_rules
  ADD CONSTRAINT fk_shelf_life_rules_ingredient_same_kitchen
  FOREIGN KEY (kitchen_id, ingredient_id)
  REFERENCES ingredients(kitchen_id, id);

-- Activity and AI audit rows had no ownership key at all.  Recover scope only
-- where it is deterministic.  Rows with ambiguous provenance are retained in
-- an inactive quarantine workspace so they cannot be disclosed to an active
-- tenant.  New writes always provide both keys explicitly.
ALTER TABLE activity_events
  ADD COLUMN kitchen_id VARCHAR(80);

ALTER TABLE activity_events
  ADD COLUMN location_id VARCHAR(100);

INSERT INTO kitchens
  (id, name, timezone, currency, active)
VALUES
  ('kitchen-legacy-unattributed',
   'Legacy unattributed records', 'UTC', 'USD', FALSE);

INSERT INTO kitchen_locations
  (id, kitchen_id, name, location_type, active)
VALUES
  ('location-legacy-unattributed',
   'kitchen-legacy-unattributed',
   'Legacy unattributed records', 'KITCHEN', FALSE);

-- These three rows are the explicitly labelled V2 demo seed and therefore
-- have known ownership.
UPDATE activity_events
SET kitchen_id = 'kitchen-default',
    location_id = 'location-main'
WHERE id IN ('EV-DEMO-001', 'EV-DEMO-002', 'EV-DEMO-003');

-- Runtime-created activities carry their kitchen in the transactional outbox.
UPDATE activity_events
SET kitchen_id = (
  SELECT MIN(event.kitchen_id)
  FROM analytics_outbox event
  WHERE event.entity_type = 'activity'
    AND event.entity_id = activity_events.id
  HAVING COUNT(DISTINCT event.kitchen_id) = 1
)
WHERE kitchen_id IS NULL;

UPDATE activity_events
SET location_id = (
  SELECT MIN(location.id)
  FROM kitchen_locations location
  WHERE location.kitchen_id = activity_events.kitchen_id
    AND location.active = TRUE
  HAVING COUNT(*) = 1
)
WHERE kitchen_id IS NOT NULL
  AND location_id IS NULL;

-- A database with exactly one active location has only one possible legacy
-- scope, including installations predating the transactional outbox.
UPDATE activity_events
SET kitchen_id = (
      SELECT MIN(location.kitchen_id)
      FROM kitchen_locations location
      WHERE location.active = TRUE
      HAVING COUNT(*) = 1
    ),
    location_id = (
      SELECT MIN(location.id)
      FROM kitchen_locations location
      WHERE location.active = TRUE
      HAVING COUNT(*) = 1
    )
WHERE kitchen_id IS NULL OR location_id IS NULL;

UPDATE activity_events
SET kitchen_id = 'kitchen-legacy-unattributed',
    location_id = 'location-legacy-unattributed'
WHERE kitchen_id IS NULL OR location_id IS NULL;

ALTER TABLE activity_events
  ALTER COLUMN kitchen_id SET NOT NULL;

ALTER TABLE activity_events
  ALTER COLUMN location_id SET NOT NULL;

ALTER TABLE activity_events
  ADD CONSTRAINT fk_activity_events_scope
  FOREIGN KEY (kitchen_id, location_id)
  REFERENCES kitchen_locations(kitchen_id, id);

CREATE INDEX idx_activity_events_scope_time
  ON activity_events(kitchen_id, location_id, occurred_at DESC);

ALTER TABLE ai_actions
  ADD COLUMN kitchen_id VARCHAR(80);

ALTER TABLE ai_actions
  ADD COLUMN location_id VARCHAR(100);

UPDATE ai_actions
SET kitchen_id = (
      SELECT MIN(location.kitchen_id)
      FROM kitchen_locations location
      WHERE location.active = TRUE
      HAVING COUNT(*) = 1
    ),
    location_id = (
      SELECT MIN(location.id)
      FROM kitchen_locations location
      WHERE location.active = TRUE
      HAVING COUNT(*) = 1
    )
WHERE kitchen_id IS NULL OR location_id IS NULL;

UPDATE ai_actions
SET kitchen_id = 'kitchen-legacy-unattributed',
    location_id = 'location-legacy-unattributed'
WHERE kitchen_id IS NULL OR location_id IS NULL;

ALTER TABLE ai_actions
  ALTER COLUMN kitchen_id SET NOT NULL;

ALTER TABLE ai_actions
  ALTER COLUMN location_id SET NOT NULL;

ALTER TABLE ai_actions
  ADD CONSTRAINT fk_ai_actions_scope
  FOREIGN KEY (kitchen_id, location_id)
  REFERENCES kitchen_locations(kitchen_id, id);

CREATE INDEX idx_ai_actions_scope_time
  ON ai_actions(kitchen_id, location_id, occurred_at DESC);

-- A kitchen-event proposal is applied at one inventory location, so persist
-- that location instead of consulting mutable runtime configuration later.
ALTER TABLE kitchen_event_proposals
  ADD COLUMN location_id VARCHAR(100);

UPDATE kitchen_event_proposals
SET location_id = (
  SELECT MIN(location.id)
  FROM kitchen_locations location
  WHERE location.kitchen_id = kitchen_event_proposals.kitchen_id
    AND location.active = TRUE
  HAVING COUNT(*) = 1
)
WHERE location_id IS NULL;

-- Ambiguous legacy proposals must never be assigned to an arbitrary active
-- location.  Preserve them under an inactive, per-kitchen quarantine location.
INSERT INTO kitchen_locations
  (id, kitchen_id, name, location_type, active)
SELECT
  CONCAT('legacy-unscoped-', kitchen.id),
  kitchen.id,
  CONCAT('Legacy unscoped proposals ', kitchen.id),
  'KITCHEN',
  FALSE
FROM kitchens kitchen
WHERE EXISTS (
  SELECT 1
  FROM kitchen_event_proposals proposal
  WHERE proposal.kitchen_id = kitchen.id
    AND proposal.location_id IS NULL
);

UPDATE kitchen_event_proposals
SET location_id = CONCAT('legacy-unscoped-', kitchen_id)
WHERE location_id IS NULL;

ALTER TABLE kitchen_event_proposals
  ALTER COLUMN location_id SET NOT NULL;

ALTER TABLE kitchen_event_proposals
  ADD CONSTRAINT fk_kitchen_event_proposals_scope
  FOREIGN KEY (kitchen_id, location_id)
  REFERENCES kitchen_locations(kitchen_id, id);

CREATE INDEX idx_kitchen_event_proposals_scope_pending
  ON kitchen_event_proposals(kitchen_id, location_id, status, expires_at);
