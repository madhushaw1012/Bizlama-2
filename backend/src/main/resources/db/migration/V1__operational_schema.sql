CREATE TABLE ingredients (
  id VARCHAR(80) PRIMARY KEY,
  name VARCHAR(160) NOT NULL,
  base_unit VARCHAR(24) NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE ingredient_aliases (
  alias_normalized VARCHAR(220) PRIMARY KEY,
  ingredient_id VARCHAR(80) NOT NULL REFERENCES ingredients(id),
  display_name VARCHAR(220) NOT NULL,
  brand VARCHAR(120),
  confidence DECIMAL(5,4) NOT NULL,
  source VARCHAR(40) NOT NULL
);

CREATE TABLE dishes (
  id VARCHAR(80) PRIMARY KEY,
  name VARCHAR(160) NOT NULL,
  price DECIMAL(12,2) NOT NULL,
  active_recipe_version_id VARCHAR(100),
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE recipe_versions (
  id VARCHAR(100) PRIMARY KEY,
  dish_id VARCHAR(80) NOT NULL REFERENCES dishes(id),
  version_number INTEGER NOT NULL,
  change_reason VARCHAR(500) NOT NULL,
  active BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (dish_id, version_number)
);

ALTER TABLE dishes
  ADD CONSTRAINT fk_active_recipe
  FOREIGN KEY (active_recipe_version_id)
  REFERENCES recipe_versions(id);

CREATE TABLE recipe_ingredients (
  recipe_version_id VARCHAR(100) NOT NULL
    REFERENCES recipe_versions(id) ON DELETE CASCADE,
  ingredient_id VARCHAR(80) NOT NULL
    REFERENCES ingredients(id),
  quantity DECIMAL(12,3) NOT NULL,
  unit VARCHAR(24) NOT NULL,
  PRIMARY KEY (recipe_version_id, ingredient_id)
);

CREATE TABLE recipe_steps (
  recipe_version_id VARCHAR(100) NOT NULL
    REFERENCES recipe_versions(id) ON DELETE CASCADE,
  step_number INTEGER NOT NULL,
  instruction VARCHAR(1000) NOT NULL,
  PRIMARY KEY (recipe_version_id, step_number)
);

CREATE TABLE stock_lots (
  id VARCHAR(100) PRIMARY KEY,
  ingredient_id VARCHAR(80) NOT NULL REFERENCES ingredients(id),
  quantity_remaining DECIMAL(14,3) NOT NULL,
  unit VARCHAR(24) NOT NULL,
  purchased_at DATE NOT NULL,
  expires_at DATE NOT NULL,
  source VARCHAR(80) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE stock_movements (
  id VARCHAR(100) PRIMARY KEY,
  stock_lot_id VARCHAR(100),
  ingredient_id VARCHAR(80) NOT NULL REFERENCES ingredients(id),
  movement_type VARCHAR(40) NOT NULL,
  quantity_change DECIMAL(14,3) NOT NULL,
  unit VARCHAR(24) NOT NULL,
  reference_type VARCHAR(50) NOT NULL,
  reference_id VARCHAR(120) NOT NULL,
  occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE customer_orders (
  id VARCHAR(100) PRIMARY KEY,
  total DECIMAL(12,2) NOT NULL,
  status VARCHAR(30) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE order_items (
  order_id VARCHAR(100) NOT NULL
    REFERENCES customer_orders(id) ON DELETE CASCADE,
  line_number INTEGER NOT NULL,
  dish_id VARCHAR(80) NOT NULL REFERENCES dishes(id),
  quantity INTEGER NOT NULL,
  unit_price DECIMAL(12,2) NOT NULL,
  PRIMARY KEY (order_id, line_number)
);

CREATE TABLE feedback (
  id VARCHAR(100) PRIMARY KEY,
  recipe_id VARCHAR(100) NOT NULL,
  feedback_text VARCHAR(2000) NOT NULL,
  rating INTEGER NOT NULL,
  occurred_at DATE NOT NULL,
  source VARCHAR(50) NOT NULL
);

CREATE TABLE recipe_experiments (
  id VARCHAR(100) PRIMARY KEY,
  dish_id VARCHAR(80) NOT NULL REFERENCES dishes(id),
  theme VARCHAR(200) NOT NULL,
  theme_count INTEGER NOT NULL,
  feedback_count INTEGER NOT NULL,
  current_value DECIMAL(12,3) NOT NULL,
  proposed_value DECIMAL(12,3) NOT NULL,
  test_duration_days INTEGER NOT NULL,
  status VARCHAR(30) NOT NULL,
  approved_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE activity_events (
  id VARCHAR(100) PRIMARY KEY,
  event_type VARCHAR(50) NOT NULL,
  description VARCHAR(1000) NOT NULL,
  occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE ai_actions (
  id VARCHAR(100) PRIMARY KEY,
  action_type VARCHAR(60) NOT NULL,
  original_input VARCHAR(2000) NOT NULL,
  normalized_item_id VARCHAR(100),
  confidence DECIMAL(5,4) NOT NULL,
  decision VARCHAR(40) NOT NULL,
  explanation VARCHAR(1000) NOT NULL,
  occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE receipt_imports (
  id VARCHAR(100) PRIMARY KEY,
  original_filename VARCHAR(300) NOT NULL,
  object_uri VARCHAR(1000) NOT NULL,
  status VARCHAR(40) NOT NULL,
  merchant VARCHAR(300),
  purchase_date DATE,
  total DECIMAL(12,2),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  confirmed_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE receipt_items (
  id VARCHAR(100) PRIMARY KEY,
  receipt_id VARCHAR(100) NOT NULL
    REFERENCES receipt_imports(id) ON DELETE CASCADE,
  raw_name VARCHAR(500) NOT NULL,
  canonical_name VARCHAR(160),
  quantity DECIMAL(12,3) NOT NULL,
  ingredient_id VARCHAR(80),
  unit VARCHAR(24) NOT NULL,
  unit_price DECIMAL(12,2),
  confidence DECIMAL(5,4) NOT NULL,
  selected BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE INDEX idx_stock_lots_ingredient ON stock_lots(ingredient_id);
CREATE INDEX idx_stock_lots_expiry ON stock_lots(expires_at);
CREATE INDEX idx_stock_movements_occurred ON stock_movements(occurred_at);
CREATE INDEX idx_orders_created ON customer_orders(created_at);
CREATE INDEX idx_activity_occurred ON activity_events(occurred_at);