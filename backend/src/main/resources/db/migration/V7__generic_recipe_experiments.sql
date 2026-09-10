ALTER TABLE recipe_experiments
ADD COLUMN metric_name VARCHAR(120);

ALTER TABLE recipe_experiments
ADD COLUMN value_unit VARCHAR(32);

UPDATE recipe_experiments
SET metric_name = 'Butter',
    value_unit = 'g'
WHERE id = 'EXP-PANEER-001';

UPDATE recipe_experiments
SET metric_name = 'Recipe value'
WHERE metric_name IS NULL;

UPDATE recipe_experiments
SET value_unit = 'unit'
WHERE value_unit IS NULL;

ALTER TABLE recipe_experiments
ALTER COLUMN metric_name SET NOT NULL;

ALTER TABLE recipe_experiments
ALTER COLUMN value_unit SET NOT NULL;