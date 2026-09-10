CREATE TABLE shelf_life_rules (
  id VARCHAR(100) PRIMARY KEY,
  kitchen_id VARCHAR(80) NOT NULL REFERENCES kitchens(id),
  ingredient_id VARCHAR(80) NOT NULL REFERENCES ingredients(id),
  storage_method VARCHAR(40) NOT NULL,
  min_days INTEGER NOT NULL,
  max_days INTEGER NOT NULL,
  priority INTEGER NOT NULL DEFAULT 100,
  source_name VARCHAR(160) NOT NULL,
  source_version VARCHAR(80),
  source_url VARCHAR(1000),
  reviewed_at TIMESTAMP WITH TIME ZONE,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CHECK (min_days >= 0),
  CHECK (max_days >= min_days),
  UNIQUE (kitchen_id, ingredient_id, storage_method, source_name)
);


INSERT INTO shelf_life_rules (
  id,
  kitchen_id,
  ingredient_id,
  storage_method,
  min_days,
  max_days,
  priority,
  source_name,
  source_version,
  reviewed_at
) VALUES
  (
    'sl-paneer-reviewed',
    'kitchen-default',
    'paneer',
    'REFRIGERATED',
    4,
    4,
    10,
    'reviewed-demo-guidance',
    '1',
    CURRENT_TIMESTAMP
  ),
  (
    'sl-bread-reviewed',
    'kitchen-default',
    'bread',
    'ROOM_TEMPERATURE',
    3,
    3,
    10,
    'reviewed-demo-guidance',
    '1',
    CURRENT_TIMESTAMP
  ),
  (
    'sl-butter-reviewed',
    'kitchen-default',
    'butter',
    'REFRIGERATED',
    14,
    14,
    10,
    'reviewed-demo-guidance',
    '1',
    CURRENT_TIMESTAMP
  ),
  (
    'sl-tomatoes-reviewed',
    'kitchen-default',
    'tomatoes',
    'REFRIGERATED',
    5,
    5,
    10,
    'reviewed-demo-guidance',
    '1',
    CURRENT_TIMESTAMP
  ),
  (
    'sl-milk-reviewed',
    'kitchen-default',
    'milk',
    'REFRIGERATED',
    5,
    5,
    10,
    'reviewed-demo-guidance',
    '1',
    CURRENT_TIMESTAMP
  ),
  (
    'sl-dosa-batter-reviewed',
    'kitchen-default',
    'dosa-batter',
    'REFRIGERATED',
    5,
    5,
    10,
    'reviewed-demo-guidance',
    '1',
    CURRENT_TIMESTAMP
  );


CREATE INDEX idx_shelf_life_lookup
  ON shelf_life_rules(kitchen_id, ingredient_id, active, priority);