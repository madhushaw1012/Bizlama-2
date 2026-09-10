CREATE TABLE kitchens (
  id VARCHAR(80) PRIMARY KEY,
  name VARCHAR(180) NOT NULL,
  timezone VARCHAR(60) NOT NULL DEFAULT 'Asia/Kolkata',
  currency VARCHAR(3) NOT NULL DEFAULT 'INR',
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO kitchens (id, name)
VALUES ('kitchen-default', 'My kitchen');


CREATE TABLE business_users (
  id VARCHAR(100) PRIMARY KEY,
  kitchen_id VARCHAR(80) NOT NULL REFERENCES kitchens(id),
  identity_subject VARCHAR(200) NOT NULL,
  email VARCHAR(320) NOT NULL,
  display_name VARCHAR(160) NOT NULL,
  role VARCHAR(40) NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (kitchen_id, identity_subject),
  UNIQUE (kitchen_id, email)
);


CREATE TABLE kitchen_locations (
  id VARCHAR(100) PRIMARY KEY,
  kitchen_id VARCHAR(80) NOT NULL REFERENCES kitchens(id),
  name VARCHAR(160) NOT NULL,
  location_type VARCHAR(40) NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (kitchen_id, name)
);

INSERT INTO kitchen_locations (
  id,
  kitchen_id,
  name,
  location_type
) VALUES (
  'location-main',
  'kitchen-default',
  'Main kitchen',
  'KITCHEN'
);


CREATE TABLE suppliers (
  id VARCHAR(100) PRIMARY KEY,
  kitchen_id VARCHAR(80) NOT NULL REFERENCES kitchens(id),
  name VARCHAR(200) NOT NULL,
  phone VARCHAR(40),
  email VARCHAR(320),
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (kitchen_id, name)
);


CREATE TABLE catalog_categories (
  id VARCHAR(100) PRIMARY KEY,
  kitchen_id VARCHAR(80) NOT NULL REFERENCES kitchens(id),
  name VARCHAR(120) NOT NULL,
  parent_id VARCHAR(100) REFERENCES catalog_categories(id),
  UNIQUE (kitchen_id, name)
);


CREATE TABLE unit_conversions (
  ingredient_id VARCHAR(80) NOT NULL REFERENCES ingredients(id),
  from_unit VARCHAR(24) NOT NULL,
  to_unit VARCHAR(24) NOT NULL,
  multiplier DECIMAL(18,6) NOT NULL,
  source VARCHAR(80) NOT NULL,
  PRIMARY KEY (ingredient_id, from_unit, to_unit)
);


ALTER TABLE ingredients
  ADD COLUMN kitchen_id VARCHAR(80) DEFAULT 'kitchen-default' NOT NULL;

ALTER TABLE ingredients
  ADD CONSTRAINT fk_ingredients_kitchen
  FOREIGN KEY (kitchen_id) REFERENCES kitchens(id);

ALTER TABLE ingredients
  ADD COLUMN category_id VARCHAR(100);

ALTER TABLE ingredients
  ADD CONSTRAINT fk_ingredients_category
  FOREIGN KEY (category_id) REFERENCES catalog_categories(id);

ALTER TABLE ingredients
  ADD COLUMN reorder_point DECIMAL(14,3);


ALTER TABLE dishes
  ADD COLUMN kitchen_id VARCHAR(80) DEFAULT 'kitchen-default' NOT NULL;

ALTER TABLE dishes
  ADD CONSTRAINT fk_dishes_kitchen
  FOREIGN KEY (kitchen_id) REFERENCES kitchens(id);

ALTER TABLE dishes
  ADD COLUMN category_id VARCHAR(100);

ALTER TABLE dishes
  ADD CONSTRAINT fk_dishes_category
  FOREIGN KEY (category_id) REFERENCES catalog_categories(id);

ALTER TABLE dishes
  ADD COLUMN preparation_minutes INTEGER;


ALTER TABLE stock_lots
  ADD COLUMN kitchen_id VARCHAR(80) DEFAULT 'kitchen-default' NOT NULL;

ALTER TABLE stock_lots
  ADD CONSTRAINT fk_stock_lots_kitchen
  FOREIGN KEY (kitchen_id) REFERENCES kitchens(id);

ALTER TABLE stock_lots
  ADD COLUMN location_id VARCHAR(100) DEFAULT 'location-main' NOT NULL;

ALTER TABLE stock_lots
  ADD CONSTRAINT fk_stock_lots_location
  FOREIGN KEY (location_id) REFERENCES kitchen_locations(id);

ALTER TABLE stock_lots
  ADD COLUMN supplier_id VARCHAR(100);

ALTER TABLE stock_lots
  ADD CONSTRAINT fk_stock_lots_supplier
  FOREIGN KEY (supplier_id) REFERENCES suppliers(id);

ALTER TABLE stock_lots
  ADD COLUMN batch_code VARCHAR(120);

ALTER TABLE stock_lots
  ADD COLUMN unit_cost DECIMAL(12,2);


ALTER TABLE customer_orders
  ADD COLUMN kitchen_id VARCHAR(80) DEFAULT 'kitchen-default' NOT NULL;

ALTER TABLE customer_orders
  ADD CONSTRAINT fk_orders_kitchen
  FOREIGN KEY (kitchen_id) REFERENCES kitchens(id);

ALTER TABLE customer_orders
  ADD COLUMN location_id VARCHAR(100) DEFAULT 'location-main' NOT NULL;

ALTER TABLE customer_orders
  ADD CONSTRAINT fk_orders_location
  FOREIGN KEY (location_id) REFERENCES kitchen_locations(id);

ALTER TABLE customer_orders
  ADD COLUMN channel VARCHAR(40) DEFAULT 'COUNTER' NOT NULL;

ALTER TABLE customer_orders
  ADD COLUMN customer_name VARCHAR(180);

ALTER TABLE customer_orders
  ADD COLUMN customer_reference VARCHAR(180);

ALTER TABLE customer_orders
  ADD COLUMN notes VARCHAR(1000);

ALTER TABLE customer_orders
  ADD COLUMN payment_status VARCHAR(30) DEFAULT 'PENDING' NOT NULL;

ALTER TABLE customer_orders
  ADD COLUMN updated_at TIMESTAMP WITH TIME ZONE
    DEFAULT CURRENT_TIMESTAMP NOT NULL;


CREATE TABLE order_status_history (
  id VARCHAR(100) PRIMARY KEY,
  order_id VARCHAR(100) NOT NULL
    REFERENCES customer_orders(id) ON DELETE CASCADE,
  status VARCHAR(30) NOT NULL,
  changed_by VARCHAR(100),
  note VARCHAR(500),
  changed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);


INSERT INTO order_status_history (
  id,
  order_id,
  status,
  changed_at
)
SELECT
  'history-' || id,
  id,
  status,
  created_at
FROM customer_orders;


CREATE TABLE analytics_outbox (
  id VARCHAR(100) PRIMARY KEY,
  kitchen_id VARCHAR(80) NOT NULL REFERENCES kitchens(id),
  event_type VARCHAR(80) NOT NULL,
  aggregate_type VARCHAR(80) NOT NULL,
  aggregate_id VARCHAR(120) NOT NULL,
  payload_json TEXT NOT NULL,
  occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
  published_at TIMESTAMP WITH TIME ZONE,
  attempt_count INTEGER NOT NULL DEFAULT 0,
  last_error VARCHAR(1000)
);


CREATE INDEX idx_ingredients_kitchen_name ON ingredients(kitchen_id, name);
CREATE INDEX idx_aliases_ingredient ON ingredient_aliases(ingredient_id);
CREATE INDEX idx_dishes_kitchen_name ON dishes(kitchen_id, name);
CREATE INDEX idx_recipe_versions_dish_created ON recipe_versions(dish_id, created_at DESC);
CREATE INDEX idx_stock_lots_kitchen_expiry ON stock_lots(kitchen_id, expires_at, ingredient_id);
CREATE INDEX idx_stock_lots_kitchen_quantity ON stock_lots(kitchen_id, quantity_remaining);
CREATE INDEX idx_stock_movements_ingredient_time ON stock_movements(ingredient_id, occurred_at DESC);
CREATE INDEX idx_orders_kitchen_status_time ON customer_orders(kitchen_id, status, created_at DESC);
CREATE INDEX idx_order_items_dish ON order_items(dish_id, order_id);
CREATE INDEX idx_order_status_history_order_time ON order_status_history(order_id, changed_at DESC);
CREATE INDEX idx_receipts_status_time ON receipt_imports(status, created_at DESC);
CREATE INDEX idx_receipt_items_receipt ON receipt_items(receipt_id);
CREATE INDEX idx_feedback_recipe_time ON feedback(recipe_id, occurred_at DESC);
CREATE INDEX idx_ai_actions_time ON ai_actions(occurred_at DESC);
CREATE INDEX idx_outbox_pending ON analytics_outbox(published_at, occurred_at);