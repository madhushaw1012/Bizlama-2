-- Expand the default demonstration workspace into a realistic casual Indian
-- restaurant. Deterministic identifiers and conflict-safe inserts preserve
-- operator-created data if an installation already contains a matching row.

-- -----------------------------------------------------------------------------
-- Pantry catalogue
-- -----------------------------------------------------------------------------

INSERT INTO ingredients
  (id, name, base_unit, active, kitchen_id, reorder_point)
VALUES
  ('demo-capsicum', 'Capsicum', 'g', TRUE, 'kitchen-default', 500),
  ('demo-green-peas', 'Green peas', 'g', TRUE, 'kitchen-default', 600),
  ('demo-carrots', 'Carrots', 'g', TRUE, 'kitchen-default', 600),
  ('demo-cauliflower', 'Cauliflower', 'g', TRUE, 'kitchen-default', 700),
  ('demo-spinach', 'Spinach', 'g', TRUE, 'kitchen-default', 800),
  ('demo-cucumber', 'Cucumber', 'g', TRUE, 'kitchen-default', 500),
  ('demo-lemon', 'Lemon', 'each', TRUE, 'kitchen-default', 12),
  ('demo-mint', 'Mint', 'g', TRUE, 'kitchen-default', 100),
  ('demo-curry-leaves', 'Curry leaves', 'g', TRUE, 'kitchen-default', 80),
  ('demo-coconut', 'Fresh coconut', 'g', TRUE, 'kitchen-default', 400),
  ('demo-tamarind', 'Tamarind', 'g', TRUE, 'kitchen-default', 250),
  ('demo-kidney-beans', 'Kidney beans', 'g', TRUE, 'kitchen-default', 1200),
  ('demo-semolina', 'Semolina', 'g', TRUE, 'kitchen-default', 1200),
  ('demo-flattened-rice', 'Flattened rice', 'g', TRUE, 'kitchen-default', 1000),
  ('demo-peanuts', 'Peanuts', 'g', TRUE, 'kitchen-default', 500),
  ('demo-cashews', 'Cashews', 'g', TRUE, 'kitchen-default', 400),
  ('demo-raisins', 'Raisins', 'g', TRUE, 'kitchen-default', 300),
  ('demo-fresh-cream', 'Fresh cream', 'ml', TRUE, 'kitchen-default', 800),
  ('demo-cheese', 'Cheese', 'g', TRUE, 'kitchen-default', 700),
  ('demo-chicken', 'Chicken', 'g', TRUE, 'kitchen-default', 2000),
  ('demo-mango-pulp', 'Mango pulp', 'g', TRUE, 'kitchen-default', 1000),
  ('demo-coffee', 'Filter coffee', 'g', TRUE, 'kitchen-default', 350),
  ('demo-cardamom', 'Cardamom', 'g', TRUE, 'kitchen-default', 80),
  ('demo-cinnamon', 'Cinnamon', 'g', TRUE, 'kitchen-default', 80),
  ('demo-cloves', 'Cloves', 'g', TRUE, 'kitchen-default', 60),
  ('demo-black-pepper', 'Black pepper', 'g', TRUE, 'kitchen-default', 120),
  ('demo-coriander-powder', 'Coriander powder', 'g', TRUE, 'kitchen-default', 300),
  ('demo-kasuri-methi', 'Kasuri methi', 'g', TRUE, 'kitchen-default', 100),
  ('demo-chaat-masala', 'Chaat masala', 'g', TRUE, 'kitchen-default', 150),
  ('demo-baking-soda', 'Baking soda', 'g', TRUE, 'kitchen-default', 150),
  ('demo-soda-water', 'Soda water', 'ml', TRUE, 'kitchen-default', 2000),
  ('demo-idli-batter', 'Idli batter', 'g', TRUE, 'kitchen-default', 2500),
  ('demo-milk-powder', 'Milk powder', 'g', TRUE, 'kitchen-default', 600)
ON CONFLICT DO NOTHING;

INSERT INTO ingredient_aliases
  (alias_normalized, ingredient_id, display_name, brand, confidence, source,
   kitchen_id)
VALUES
  ('capsicum', 'demo-capsicum', 'Capsicum', NULL, 1.0000, 'synthetic-seed', 'kitchen-default'),
  ('bell pepper', 'demo-capsicum', 'Bell pepper', NULL, 0.9900, 'synthetic-seed', 'kitchen-default'),
  ('green peas', 'demo-green-peas', 'Green peas', NULL, 1.0000, 'synthetic-seed', 'kitchen-default'),
  ('matar', 'demo-green-peas', 'Matar', NULL, 0.9800, 'synthetic-seed', 'kitchen-default'),
  ('carrots', 'demo-carrots', 'Carrots', NULL, 1.0000, 'synthetic-seed', 'kitchen-default'),
  ('cauliflower', 'demo-cauliflower', 'Cauliflower', NULL, 1.0000, 'synthetic-seed', 'kitchen-default'),
  ('gobi', 'demo-cauliflower', 'Gobi', NULL, 0.9800, 'synthetic-seed', 'kitchen-default'),
  ('spinach', 'demo-spinach', 'Spinach', NULL, 1.0000, 'synthetic-seed', 'kitchen-default'),
  ('palak', 'demo-spinach', 'Palak', NULL, 0.9900, 'synthetic-seed', 'kitchen-default'),
  ('cucumber', 'demo-cucumber', 'Cucumber', NULL, 1.0000, 'synthetic-seed', 'kitchen-default'),
  ('lemon', 'demo-lemon', 'Lemon', NULL, 1.0000, 'synthetic-seed', 'kitchen-default'),
  ('nimbu', 'demo-lemon', 'Nimbu', NULL, 0.9900, 'synthetic-seed', 'kitchen-default'),
  ('mint', 'demo-mint', 'Mint', NULL, 1.0000, 'synthetic-seed', 'kitchen-default'),
  ('pudina', 'demo-mint', 'Pudina', NULL, 0.9900, 'synthetic-seed', 'kitchen-default'),
  ('curry leaves', 'demo-curry-leaves', 'Curry leaves', NULL, 1.0000, 'synthetic-seed', 'kitchen-default'),
  ('kari patta', 'demo-curry-leaves', 'Kari patta', NULL, 0.9900, 'synthetic-seed', 'kitchen-default'),
  ('fresh coconut', 'demo-coconut', 'Fresh coconut', NULL, 1.0000, 'synthetic-seed', 'kitchen-default'),
  ('tamarind', 'demo-tamarind', 'Tamarind', NULL, 1.0000, 'synthetic-seed', 'kitchen-default'),
  ('imli', 'demo-tamarind', 'Imli', NULL, 0.9900, 'synthetic-seed', 'kitchen-default'),
  ('kidney beans', 'demo-kidney-beans', 'Kidney beans', NULL, 1.0000, 'synthetic-seed', 'kitchen-default'),
  ('rajma', 'demo-kidney-beans', 'Rajma', NULL, 0.9900, 'synthetic-seed', 'kitchen-default'),
  ('semolina', 'demo-semolina', 'Semolina', NULL, 1.0000, 'synthetic-seed', 'kitchen-default'),
  ('sooji', 'demo-semolina', 'Sooji', NULL, 0.9900, 'synthetic-seed', 'kitchen-default'),
  ('rava', 'demo-semolina', 'Rava', NULL, 0.9900, 'synthetic-seed', 'kitchen-default'),
  ('flattened rice', 'demo-flattened-rice', 'Flattened rice', NULL, 1.0000, 'synthetic-seed', 'kitchen-default'),
  ('poha', 'demo-flattened-rice', 'Poha', NULL, 0.9900, 'synthetic-seed', 'kitchen-default'),
  ('peanuts', 'demo-peanuts', 'Peanuts', NULL, 1.0000, 'synthetic-seed', 'kitchen-default'),
  ('cashews', 'demo-cashews', 'Cashews', NULL, 1.0000, 'synthetic-seed', 'kitchen-default'),
  ('kaju', 'demo-cashews', 'Kaju', NULL, 0.9900, 'synthetic-seed', 'kitchen-default'),
  ('raisins', 'demo-raisins', 'Raisins', NULL, 1.0000, 'synthetic-seed', 'kitchen-default'),
  ('fresh cream', 'demo-fresh-cream', 'Fresh cream', NULL, 1.0000, 'synthetic-seed', 'kitchen-default'),
  ('malai', 'demo-fresh-cream', 'Malai', NULL, 0.9700, 'synthetic-seed', 'kitchen-default'),
  ('cheese', 'demo-cheese', 'Cheese', NULL, 1.0000, 'synthetic-seed', 'kitchen-default'),
  ('chicken', 'demo-chicken', 'Chicken', NULL, 1.0000, 'synthetic-seed', 'kitchen-default'),
  ('mango pulp', 'demo-mango-pulp', 'Mango pulp', NULL, 1.0000, 'synthetic-seed', 'kitchen-default'),
  ('filter coffee', 'demo-coffee', 'Filter coffee', NULL, 1.0000, 'synthetic-seed', 'kitchen-default'),
  ('coffee powder', 'demo-coffee', 'Coffee powder', NULL, 0.9900, 'synthetic-seed', 'kitchen-default'),
  ('cardamom', 'demo-cardamom', 'Cardamom', NULL, 1.0000, 'synthetic-seed', 'kitchen-default'),
  ('elaichi', 'demo-cardamom', 'Elaichi', NULL, 0.9900, 'synthetic-seed', 'kitchen-default'),
  ('cinnamon', 'demo-cinnamon', 'Cinnamon', NULL, 1.0000, 'synthetic-seed', 'kitchen-default'),
  ('dalchini', 'demo-cinnamon', 'Dalchini', NULL, 0.9900, 'synthetic-seed', 'kitchen-default'),
  ('cloves', 'demo-cloves', 'Cloves', NULL, 1.0000, 'synthetic-seed', 'kitchen-default'),
  ('laung', 'demo-cloves', 'Laung', NULL, 0.9900, 'synthetic-seed', 'kitchen-default'),
  ('black pepper', 'demo-black-pepper', 'Black pepper', NULL, 1.0000, 'synthetic-seed', 'kitchen-default'),
  ('kali mirch', 'demo-black-pepper', 'Kali mirch', NULL, 0.9900, 'synthetic-seed', 'kitchen-default'),
  ('coriander powder', 'demo-coriander-powder', 'Coriander powder', NULL, 1.0000, 'synthetic-seed', 'kitchen-default'),
  ('dhania powder', 'demo-coriander-powder', 'Dhania powder', NULL, 0.9900, 'synthetic-seed', 'kitchen-default'),
  ('kasuri methi', 'demo-kasuri-methi', 'Kasuri methi', NULL, 1.0000, 'synthetic-seed', 'kitchen-default'),
  ('chaat masala', 'demo-chaat-masala', 'Chaat masala', NULL, 1.0000, 'synthetic-seed', 'kitchen-default'),
  ('baking soda', 'demo-baking-soda', 'Baking soda', NULL, 1.0000, 'synthetic-seed', 'kitchen-default'),
  ('soda water', 'demo-soda-water', 'Soda water', NULL, 1.0000, 'synthetic-seed', 'kitchen-default'),
  ('idli batter', 'demo-idli-batter', 'Idli batter', NULL, 1.0000, 'synthetic-seed', 'kitchen-default'),
  ('milk powder', 'demo-milk-powder', 'Milk powder', NULL, 1.0000, 'synthetic-seed', 'kitchen-default')
ON CONFLICT DO NOTHING;

-- -----------------------------------------------------------------------------
-- Live menu and versioned recipes
-- -----------------------------------------------------------------------------

INSERT INTO dishes
  (id, name, price, active_recipe_version_id, active, kitchen_id,
   category_id, preparation_minutes)
VALUES
  ('demo-grilled-vegetable-sandwich', 'Grilled Vegetable Sandwich', 195.00, NULL, TRUE, 'kitchen-default', 'category-sandwiches', 12),
  ('demo-idli-sambar', 'Idli Sambar', 130.00, NULL, TRUE, 'kitchen-default', 'category-south-indian', 18),
  ('demo-onion-uttapam', 'Onion Uttapam', 145.00, NULL, TRUE, 'kitchen-default', 'category-south-indian', 15),
  ('demo-vegetable-upma', 'Vegetable Upma', 115.00, NULL, TRUE, 'kitchen-default', 'category-south-indian', 14),
  ('demo-poha', 'Poha', 105.00, NULL, TRUE, 'kitchen-default', 'category-snacks', 12),
  ('demo-mango-lassi', 'Mango Lassi', 130.00, NULL, TRUE, 'kitchen-default', 'category-beverages', 5),
  ('demo-fresh-lime-soda', 'Fresh Lime Soda', 95.00, NULL, TRUE, 'kitchen-default', 'category-beverages', 4),
  ('demo-filter-coffee', 'Filter Coffee', 75.00, NULL, TRUE, 'kitchen-default', 'category-beverages', 6),
  ('demo-paneer-tikka', 'Paneer Tikka', 265.00, NULL, TRUE, 'kitchen-default', 'category-snacks', 25),
  ('demo-vegetable-samosa', 'Vegetable Samosa', 95.00, NULL, TRUE, 'kitchen-default', 'category-snacks', 20),
  ('demo-onion-pakora', 'Onion Pakora', 110.00, NULL, TRUE, 'kitchen-default', 'category-snacks', 18),
  ('demo-paneer-butter-masala', 'Paneer Butter Masala', 295.00, NULL, TRUE, 'kitchen-default', 'category-main-course', 28),
  ('demo-chana-masala', 'Chana Masala', 220.00, NULL, TRUE, 'kitchen-default', 'category-main-course', 24),
  ('demo-dal-tadka', 'Dal Tadka', 205.00, NULL, TRUE, 'kitchen-default', 'category-main-course', 22),
  ('demo-palak-paneer', 'Palak Paneer', 285.00, NULL, TRUE, 'kitchen-default', 'category-main-course', 25),
  ('demo-vegetable-biryani', 'Vegetable Biryani', 260.00, NULL, TRUE, 'kitchen-default', 'category-main-course', 35),
  ('demo-butter-chicken', 'Butter Chicken', 345.00, NULL, TRUE, 'kitchen-default', 'category-main-course', 32),
  ('demo-rajma-rice', 'Rajma Rice', 245.00, NULL, TRUE, 'kitchen-default', 'category-main-course', 30),
  ('demo-rice-kheer', 'Rice Kheer', 125.00, NULL, TRUE, 'kitchen-default', 'category-desserts', 20),
  ('demo-gulab-jamun', 'Gulab Jamun', 115.00, NULL, TRUE, 'kitchen-default', 'category-desserts', 25)
ON CONFLICT DO NOTHING;

INSERT INTO recipe_versions
  (id, dish_id, version_number, change_reason, active, yield_quantity,
   yield_unit, created_by, approved_by, approved_at, effective_at, kitchen_id,
   yield_provenance)
VALUES
  ('demo-grilled-vegetable-sandwich-v1', 'demo-grilled-vegetable-sandwich', 1, 'Synthetic restaurant baseline', TRUE, 1, 'each', 'synthetic-restaurant-seed', 'synthetic-restaurant-seed', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'kitchen-default', 'OPERATOR_ENTERED'),
  ('demo-idli-sambar-v1', 'demo-idli-sambar', 1, 'Synthetic restaurant baseline', TRUE, 1, 'each', 'synthetic-restaurant-seed', 'synthetic-restaurant-seed', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'kitchen-default', 'OPERATOR_ENTERED'),
  ('demo-onion-uttapam-v1', 'demo-onion-uttapam', 1, 'Synthetic restaurant baseline', TRUE, 1, 'each', 'synthetic-restaurant-seed', 'synthetic-restaurant-seed', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'kitchen-default', 'OPERATOR_ENTERED'),
  ('demo-vegetable-upma-v1', 'demo-vegetable-upma', 1, 'Synthetic restaurant baseline', TRUE, 1, 'each', 'synthetic-restaurant-seed', 'synthetic-restaurant-seed', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'kitchen-default', 'OPERATOR_ENTERED'),
  ('demo-poha-v1', 'demo-poha', 1, 'Synthetic restaurant baseline', TRUE, 1, 'each', 'synthetic-restaurant-seed', 'synthetic-restaurant-seed', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'kitchen-default', 'OPERATOR_ENTERED'),
  ('demo-mango-lassi-v1', 'demo-mango-lassi', 1, 'Synthetic restaurant baseline', TRUE, 1, 'each', 'synthetic-restaurant-seed', 'synthetic-restaurant-seed', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'kitchen-default', 'OPERATOR_ENTERED'),
  ('demo-fresh-lime-soda-v1', 'demo-fresh-lime-soda', 1, 'Synthetic restaurant baseline', TRUE, 1, 'each', 'synthetic-restaurant-seed', 'synthetic-restaurant-seed', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'kitchen-default', 'OPERATOR_ENTERED'),
  ('demo-filter-coffee-v1', 'demo-filter-coffee', 1, 'Synthetic restaurant baseline', TRUE, 1, 'each', 'synthetic-restaurant-seed', 'synthetic-restaurant-seed', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'kitchen-default', 'OPERATOR_ENTERED'),
  ('demo-paneer-tikka-v1', 'demo-paneer-tikka', 1, 'Synthetic restaurant baseline', TRUE, 1, 'each', 'synthetic-restaurant-seed', 'synthetic-restaurant-seed', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'kitchen-default', 'OPERATOR_ENTERED'),
  ('demo-vegetable-samosa-v1', 'demo-vegetable-samosa', 1, 'Synthetic restaurant baseline', TRUE, 1, 'each', 'synthetic-restaurant-seed', 'synthetic-restaurant-seed', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'kitchen-default', 'OPERATOR_ENTERED'),
  ('demo-onion-pakora-v1', 'demo-onion-pakora', 1, 'Synthetic restaurant baseline', TRUE, 1, 'each', 'synthetic-restaurant-seed', 'synthetic-restaurant-seed', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'kitchen-default', 'OPERATOR_ENTERED'),
  ('demo-paneer-butter-masala-v1', 'demo-paneer-butter-masala', 1, 'Synthetic restaurant baseline', TRUE, 1, 'each', 'synthetic-restaurant-seed', 'synthetic-restaurant-seed', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'kitchen-default', 'OPERATOR_ENTERED'),
  ('demo-chana-masala-v1', 'demo-chana-masala', 1, 'Synthetic restaurant baseline', TRUE, 1, 'each', 'synthetic-restaurant-seed', 'synthetic-restaurant-seed', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'kitchen-default', 'OPERATOR_ENTERED'),
  ('demo-dal-tadka-v1', 'demo-dal-tadka', 1, 'Synthetic restaurant baseline', TRUE, 1, 'each', 'synthetic-restaurant-seed', 'synthetic-restaurant-seed', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'kitchen-default', 'OPERATOR_ENTERED'),
  ('demo-palak-paneer-v1', 'demo-palak-paneer', 1, 'Synthetic restaurant baseline', TRUE, 1, 'each', 'synthetic-restaurant-seed', 'synthetic-restaurant-seed', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'kitchen-default', 'OPERATOR_ENTERED'),
  ('demo-vegetable-biryani-v1', 'demo-vegetable-biryani', 1, 'Synthetic restaurant baseline', TRUE, 1, 'each', 'synthetic-restaurant-seed', 'synthetic-restaurant-seed', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'kitchen-default', 'OPERATOR_ENTERED'),
  ('demo-butter-chicken-v1', 'demo-butter-chicken', 1, 'Synthetic restaurant baseline', TRUE, 1, 'each', 'synthetic-restaurant-seed', 'synthetic-restaurant-seed', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'kitchen-default', 'OPERATOR_ENTERED'),
  ('demo-rajma-rice-v1', 'demo-rajma-rice', 1, 'Synthetic restaurant baseline', TRUE, 1, 'each', 'synthetic-restaurant-seed', 'synthetic-restaurant-seed', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'kitchen-default', 'OPERATOR_ENTERED'),
  ('demo-rice-kheer-v1', 'demo-rice-kheer', 1, 'Synthetic restaurant baseline', TRUE, 1, 'each', 'synthetic-restaurant-seed', 'synthetic-restaurant-seed', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'kitchen-default', 'OPERATOR_ENTERED'),
  ('demo-gulab-jamun-v1', 'demo-gulab-jamun', 1, 'Synthetic restaurant baseline', TRUE, 1, 'each', 'synthetic-restaurant-seed', 'synthetic-restaurant-seed', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'kitchen-default', 'OPERATOR_ENTERED')
ON CONFLICT DO NOTHING;

UPDATE dishes
SET active_recipe_version_id = CASE id
  WHEN 'demo-grilled-vegetable-sandwich' THEN 'demo-grilled-vegetable-sandwich-v1'
  WHEN 'demo-idli-sambar' THEN 'demo-idli-sambar-v1'
  WHEN 'demo-onion-uttapam' THEN 'demo-onion-uttapam-v1'
  WHEN 'demo-vegetable-upma' THEN 'demo-vegetable-upma-v1'
  WHEN 'demo-poha' THEN 'demo-poha-v1'
  WHEN 'demo-mango-lassi' THEN 'demo-mango-lassi-v1'
  WHEN 'demo-fresh-lime-soda' THEN 'demo-fresh-lime-soda-v1'
  WHEN 'demo-filter-coffee' THEN 'demo-filter-coffee-v1'
  WHEN 'demo-paneer-tikka' THEN 'demo-paneer-tikka-v1'
  WHEN 'demo-vegetable-samosa' THEN 'demo-vegetable-samosa-v1'
  WHEN 'demo-onion-pakora' THEN 'demo-onion-pakora-v1'
  WHEN 'demo-paneer-butter-masala' THEN 'demo-paneer-butter-masala-v1'
  WHEN 'demo-chana-masala' THEN 'demo-chana-masala-v1'
  WHEN 'demo-dal-tadka' THEN 'demo-dal-tadka-v1'
  WHEN 'demo-palak-paneer' THEN 'demo-palak-paneer-v1'
  WHEN 'demo-vegetable-biryani' THEN 'demo-vegetable-biryani-v1'
  WHEN 'demo-butter-chicken' THEN 'demo-butter-chicken-v1'
  WHEN 'demo-rajma-rice' THEN 'demo-rajma-rice-v1'
  WHEN 'demo-rice-kheer' THEN 'demo-rice-kheer-v1'
  WHEN 'demo-gulab-jamun' THEN 'demo-gulab-jamun-v1'
END
WHERE kitchen_id = 'kitchen-default'
  AND active_recipe_version_id IS NULL
  AND id LIKE 'demo-%';

INSERT INTO recipe_ingredients
  (recipe_version_id, ingredient_id, quantity, unit, kitchen_id)
VALUES
  ('demo-grilled-vegetable-sandwich-v1', 'bread', 120, 'g', 'kitchen-default'),
  ('demo-grilled-vegetable-sandwich-v1', 'butter', 10, 'g', 'kitchen-default'),
  ('demo-grilled-vegetable-sandwich-v1', 'demo-cheese', 35, 'g', 'kitchen-default'),
  ('demo-grilled-vegetable-sandwich-v1', 'demo-cucumber', 30, 'g', 'kitchen-default'),
  ('demo-grilled-vegetable-sandwich-v1', 'tomatoes', 30, 'g', 'kitchen-default'),
  ('demo-grilled-vegetable-sandwich-v1', 'demo-capsicum', 25, 'g', 'kitchen-default'),

  ('demo-idli-sambar-v1', 'demo-idli-batter', 320, 'g', 'kitchen-default'),
  ('demo-idli-sambar-v1', 'toor-dal', 70, 'g', 'kitchen-default'),
  ('demo-idli-sambar-v1', 'tomatoes', 40, 'g', 'kitchen-default'),
  ('demo-idli-sambar-v1', 'onions', 30, 'g', 'kitchen-default'),
  ('demo-idli-sambar-v1', 'demo-tamarind', 8, 'g', 'kitchen-default'),
  ('demo-idli-sambar-v1', 'mustard-seeds', 2, 'g', 'kitchen-default'),
  ('demo-idli-sambar-v1', 'demo-curry-leaves', 2, 'g', 'kitchen-default'),
  ('demo-idli-sambar-v1', 'turmeric', 1, 'g', 'kitchen-default'),
  ('demo-idli-sambar-v1', 'salt', 3, 'g', 'kitchen-default'),
  ('demo-idli-sambar-v1', 'cooking-oil', 8, 'ml', 'kitchen-default'),

  ('demo-onion-uttapam-v1', 'dosa-batter', 280, 'g', 'kitchen-default'),
  ('demo-onion-uttapam-v1', 'onions', 60, 'g', 'kitchen-default'),
  ('demo-onion-uttapam-v1', 'tomatoes', 30, 'g', 'kitchen-default'),
  ('demo-onion-uttapam-v1', 'green-chillies', 4, 'g', 'kitchen-default'),
  ('demo-onion-uttapam-v1', 'coriander', 5, 'g', 'kitchen-default'),
  ('demo-onion-uttapam-v1', 'cooking-oil', 10, 'ml', 'kitchen-default'),
  ('demo-onion-uttapam-v1', 'salt', 2, 'g', 'kitchen-default'),

  ('demo-vegetable-upma-v1', 'demo-semolina', 100, 'g', 'kitchen-default'),
  ('demo-vegetable-upma-v1', 'onions', 40, 'g', 'kitchen-default'),
  ('demo-vegetable-upma-v1', 'demo-carrots', 35, 'g', 'kitchen-default'),
  ('demo-vegetable-upma-v1', 'demo-green-peas', 30, 'g', 'kitchen-default'),
  ('demo-vegetable-upma-v1', 'demo-curry-leaves', 2, 'g', 'kitchen-default'),
  ('demo-vegetable-upma-v1', 'mustard-seeds', 2, 'g', 'kitchen-default'),
  ('demo-vegetable-upma-v1', 'green-chillies', 3, 'g', 'kitchen-default'),
  ('demo-vegetable-upma-v1', 'cooking-oil', 12, 'ml', 'kitchen-default'),
  ('demo-vegetable-upma-v1', 'salt', 3, 'g', 'kitchen-default'),

  ('demo-poha-v1', 'demo-flattened-rice', 120, 'g', 'kitchen-default'),
  ('demo-poha-v1', 'onions', 40, 'g', 'kitchen-default'),
  ('demo-poha-v1', 'potatoes', 60, 'g', 'kitchen-default'),
  ('demo-poha-v1', 'demo-peanuts', 20, 'g', 'kitchen-default'),
  ('demo-poha-v1', 'demo-curry-leaves', 2, 'g', 'kitchen-default'),
  ('demo-poha-v1', 'mustard-seeds', 2, 'g', 'kitchen-default'),
  ('demo-poha-v1', 'turmeric', 1, 'g', 'kitchen-default'),
  ('demo-poha-v1', 'green-chillies', 3, 'g', 'kitchen-default'),
  ('demo-poha-v1', 'demo-lemon', 0.5, 'each', 'kitchen-default'),
  ('demo-poha-v1', 'coriander', 5, 'g', 'kitchen-default'),
  ('demo-poha-v1', 'cooking-oil', 12, 'ml', 'kitchen-default'),
  ('demo-poha-v1', 'salt', 3, 'g', 'kitchen-default'),

  ('demo-mango-lassi-v1', 'demo-mango-pulp', 120, 'g', 'kitchen-default'),
  ('demo-mango-lassi-v1', 'curd', 180, 'g', 'kitchen-default'),
  ('demo-mango-lassi-v1', 'milk', 60, 'ml', 'kitchen-default'),
  ('demo-mango-lassi-v1', 'sugar', 15, 'g', 'kitchen-default'),
  ('demo-mango-lassi-v1', 'demo-cardamom', 0.3, 'g', 'kitchen-default'),

  ('demo-fresh-lime-soda-v1', 'demo-lemon', 1, 'each', 'kitchen-default'),
  ('demo-fresh-lime-soda-v1', 'demo-soda-water', 280, 'ml', 'kitchen-default'),
  ('demo-fresh-lime-soda-v1', 'sugar', 18, 'g', 'kitchen-default'),
  ('demo-fresh-lime-soda-v1', 'salt', 1, 'g', 'kitchen-default'),
  ('demo-fresh-lime-soda-v1', 'demo-mint', 3, 'g', 'kitchen-default'),

  ('demo-filter-coffee-v1', 'demo-coffee', 14, 'g', 'kitchen-default'),
  ('demo-filter-coffee-v1', 'milk', 180, 'ml', 'kitchen-default'),
  ('demo-filter-coffee-v1', 'sugar', 12, 'g', 'kitchen-default'),

  ('demo-paneer-tikka-v1', 'paneer', 180, 'g', 'kitchen-default'),
  ('demo-paneer-tikka-v1', 'curd', 60, 'g', 'kitchen-default'),
  ('demo-paneer-tikka-v1', 'demo-capsicum', 50, 'g', 'kitchen-default'),
  ('demo-paneer-tikka-v1', 'onions', 50, 'g', 'kitchen-default'),
  ('demo-paneer-tikka-v1', 'garam-masala', 2, 'g', 'kitchen-default'),
  ('demo-paneer-tikka-v1', 'chilli-powder', 2, 'g', 'kitchen-default'),
  ('demo-paneer-tikka-v1', 'turmeric', 1, 'g', 'kitchen-default'),
  ('demo-paneer-tikka-v1', 'demo-kasuri-methi', 1, 'g', 'kitchen-default'),
  ('demo-paneer-tikka-v1', 'cooking-oil', 10, 'ml', 'kitchen-default'),
  ('demo-paneer-tikka-v1', 'salt', 2, 'g', 'kitchen-default'),

  ('demo-vegetable-samosa-v1', 'maida', 80, 'g', 'kitchen-default'),
  ('demo-vegetable-samosa-v1', 'potatoes', 140, 'g', 'kitchen-default'),
  ('demo-vegetable-samosa-v1', 'demo-green-peas', 35, 'g', 'kitchen-default'),
  ('demo-vegetable-samosa-v1', 'cumin', 2, 'g', 'kitchen-default'),
  ('demo-vegetable-samosa-v1', 'garam-masala', 2, 'g', 'kitchen-default'),
  ('demo-vegetable-samosa-v1', 'chilli-powder', 1, 'g', 'kitchen-default'),
  ('demo-vegetable-samosa-v1', 'cooking-oil', 20, 'ml', 'kitchen-default'),
  ('demo-vegetable-samosa-v1', 'salt', 2, 'g', 'kitchen-default'),

  ('demo-onion-pakora-v1', 'besan', 90, 'g', 'kitchen-default'),
  ('demo-onion-pakora-v1', 'onions', 140, 'g', 'kitchen-default'),
  ('demo-onion-pakora-v1', 'green-chillies', 4, 'g', 'kitchen-default'),
  ('demo-onion-pakora-v1', 'coriander', 6, 'g', 'kitchen-default'),
  ('demo-onion-pakora-v1', 'demo-chaat-masala', 2, 'g', 'kitchen-default'),
  ('demo-onion-pakora-v1', 'chilli-powder', 1, 'g', 'kitchen-default'),
  ('demo-onion-pakora-v1', 'cooking-oil', 20, 'ml', 'kitchen-default'),
  ('demo-onion-pakora-v1', 'salt', 2, 'g', 'kitchen-default'),

  ('demo-paneer-butter-masala-v1', 'paneer', 180, 'g', 'kitchen-default'),
  ('demo-paneer-butter-masala-v1', 'tomatoes', 160, 'g', 'kitchen-default'),
  ('demo-paneer-butter-masala-v1', 'onions', 60, 'g', 'kitchen-default'),
  ('demo-paneer-butter-masala-v1', 'butter', 25, 'g', 'kitchen-default'),
  ('demo-paneer-butter-masala-v1', 'demo-fresh-cream', 40, 'ml', 'kitchen-default'),
  ('demo-paneer-butter-masala-v1', 'demo-cashews', 15, 'g', 'kitchen-default'),
  ('demo-paneer-butter-masala-v1', 'garam-masala', 2, 'g', 'kitchen-default'),
  ('demo-paneer-butter-masala-v1', 'demo-kasuri-methi', 2, 'g', 'kitchen-default'),
  ('demo-paneer-butter-masala-v1', 'ginger', 8, 'g', 'kitchen-default'),
  ('demo-paneer-butter-masala-v1', 'garlic', 8, 'g', 'kitchen-default'),
  ('demo-paneer-butter-masala-v1', 'chilli-powder', 1, 'g', 'kitchen-default'),
  ('demo-paneer-butter-masala-v1', 'salt', 3, 'g', 'kitchen-default'),

  ('demo-chana-masala-v1', 'chickpeas', 160, 'g', 'kitchen-default'),
  ('demo-chana-masala-v1', 'onions', 80, 'g', 'kitchen-default'),
  ('demo-chana-masala-v1', 'tomatoes', 120, 'g', 'kitchen-default'),
  ('demo-chana-masala-v1', 'ginger', 8, 'g', 'kitchen-default'),
  ('demo-chana-masala-v1', 'garlic', 8, 'g', 'kitchen-default'),
  ('demo-chana-masala-v1', 'cumin', 2, 'g', 'kitchen-default'),
  ('demo-chana-masala-v1', 'demo-coriander-powder', 4, 'g', 'kitchen-default'),
  ('demo-chana-masala-v1', 'garam-masala', 2, 'g', 'kitchen-default'),
  ('demo-chana-masala-v1', 'chilli-powder', 2, 'g', 'kitchen-default'),
  ('demo-chana-masala-v1', 'cooking-oil', 12, 'ml', 'kitchen-default'),
  ('demo-chana-masala-v1', 'salt', 3, 'g', 'kitchen-default'),

  ('demo-dal-tadka-v1', 'toor-dal', 130, 'g', 'kitchen-default'),
  ('demo-dal-tadka-v1', 'onions', 50, 'g', 'kitchen-default'),
  ('demo-dal-tadka-v1', 'tomatoes', 70, 'g', 'kitchen-default'),
  ('demo-dal-tadka-v1', 'garlic', 8, 'g', 'kitchen-default'),
  ('demo-dal-tadka-v1', 'cumin', 2, 'g', 'kitchen-default'),
  ('demo-dal-tadka-v1', 'turmeric', 1, 'g', 'kitchen-default'),
  ('demo-dal-tadka-v1', 'chilli-powder', 1, 'g', 'kitchen-default'),
  ('demo-dal-tadka-v1', 'ghee', 12, 'g', 'kitchen-default'),
  ('demo-dal-tadka-v1', 'coriander', 5, 'g', 'kitchen-default'),
  ('demo-dal-tadka-v1', 'salt', 3, 'g', 'kitchen-default'),

  ('demo-palak-paneer-v1', 'demo-spinach', 220, 'g', 'kitchen-default'),
  ('demo-palak-paneer-v1', 'paneer', 160, 'g', 'kitchen-default'),
  ('demo-palak-paneer-v1', 'onions', 60, 'g', 'kitchen-default'),
  ('demo-palak-paneer-v1', 'tomatoes', 80, 'g', 'kitchen-default'),
  ('demo-palak-paneer-v1', 'demo-fresh-cream', 30, 'ml', 'kitchen-default'),
  ('demo-palak-paneer-v1', 'garlic', 8, 'g', 'kitchen-default'),
  ('demo-palak-paneer-v1', 'ginger', 8, 'g', 'kitchen-default'),
  ('demo-palak-paneer-v1', 'garam-masala', 2, 'g', 'kitchen-default'),
  ('demo-palak-paneer-v1', 'cumin', 1, 'g', 'kitchen-default'),
  ('demo-palak-paneer-v1', 'cooking-oil', 12, 'ml', 'kitchen-default'),
  ('demo-palak-paneer-v1', 'salt', 3, 'g', 'kitchen-default'),

  ('demo-vegetable-biryani-v1', 'rice', 180, 'g', 'kitchen-default'),
  ('demo-vegetable-biryani-v1', 'demo-green-peas', 40, 'g', 'kitchen-default'),
  ('demo-vegetable-biryani-v1', 'demo-carrots', 40, 'g', 'kitchen-default'),
  ('demo-vegetable-biryani-v1', 'demo-cauliflower', 50, 'g', 'kitchen-default'),
  ('demo-vegetable-biryani-v1', 'onions', 80, 'g', 'kitchen-default'),
  ('demo-vegetable-biryani-v1', 'curd', 50, 'g', 'kitchen-default'),
  ('demo-vegetable-biryani-v1', 'demo-mint', 6, 'g', 'kitchen-default'),
  ('demo-vegetable-biryani-v1', 'ghee', 15, 'g', 'kitchen-default'),
  ('demo-vegetable-biryani-v1', 'garam-masala', 3, 'g', 'kitchen-default'),
  ('demo-vegetable-biryani-v1', 'demo-cinnamon', 1, 'g', 'kitchen-default'),
  ('demo-vegetable-biryani-v1', 'demo-cloves', 0.5, 'g', 'kitchen-default'),
  ('demo-vegetable-biryani-v1', 'salt', 3, 'g', 'kitchen-default'),

  ('demo-butter-chicken-v1', 'demo-chicken', 220, 'g', 'kitchen-default'),
  ('demo-butter-chicken-v1', 'tomatoes', 160, 'g', 'kitchen-default'),
  ('demo-butter-chicken-v1', 'butter', 30, 'g', 'kitchen-default'),
  ('demo-butter-chicken-v1', 'demo-fresh-cream', 50, 'ml', 'kitchen-default'),
  ('demo-butter-chicken-v1', 'curd', 60, 'g', 'kitchen-default'),
  ('demo-butter-chicken-v1', 'demo-cashews', 15, 'g', 'kitchen-default'),
  ('demo-butter-chicken-v1', 'garam-masala', 3, 'g', 'kitchen-default'),
  ('demo-butter-chicken-v1', 'demo-kasuri-methi', 2, 'g', 'kitchen-default'),
  ('demo-butter-chicken-v1', 'ginger', 10, 'g', 'kitchen-default'),
  ('demo-butter-chicken-v1', 'garlic', 10, 'g', 'kitchen-default'),
  ('demo-butter-chicken-v1', 'chilli-powder', 2, 'g', 'kitchen-default'),
  ('demo-butter-chicken-v1', 'salt', 3, 'g', 'kitchen-default'),

  ('demo-rajma-rice-v1', 'demo-kidney-beans', 160, 'g', 'kitchen-default'),
  ('demo-rajma-rice-v1', 'rice', 180, 'g', 'kitchen-default'),
  ('demo-rajma-rice-v1', 'onions', 80, 'g', 'kitchen-default'),
  ('demo-rajma-rice-v1', 'tomatoes', 120, 'g', 'kitchen-default'),
  ('demo-rajma-rice-v1', 'ginger', 8, 'g', 'kitchen-default'),
  ('demo-rajma-rice-v1', 'garlic', 8, 'g', 'kitchen-default'),
  ('demo-rajma-rice-v1', 'cumin', 2, 'g', 'kitchen-default'),
  ('demo-rajma-rice-v1', 'demo-coriander-powder', 4, 'g', 'kitchen-default'),
  ('demo-rajma-rice-v1', 'garam-masala', 2, 'g', 'kitchen-default'),
  ('demo-rajma-rice-v1', 'cooking-oil', 12, 'ml', 'kitchen-default'),
  ('demo-rajma-rice-v1', 'salt', 3, 'g', 'kitchen-default'),

  ('demo-rice-kheer-v1', 'rice', 45, 'g', 'kitchen-default'),
  ('demo-rice-kheer-v1', 'milk', 350, 'ml', 'kitchen-default'),
  ('demo-rice-kheer-v1', 'sugar', 45, 'g', 'kitchen-default'),
  ('demo-rice-kheer-v1', 'demo-cardamom', 0.5, 'g', 'kitchen-default'),
  ('demo-rice-kheer-v1', 'demo-cashews', 12, 'g', 'kitchen-default'),
  ('demo-rice-kheer-v1', 'demo-raisins', 10, 'g', 'kitchen-default'),
  ('demo-rice-kheer-v1', 'ghee', 5, 'g', 'kitchen-default'),

  ('demo-gulab-jamun-v1', 'demo-milk-powder', 90, 'g', 'kitchen-default'),
  ('demo-gulab-jamun-v1', 'maida', 15, 'g', 'kitchen-default'),
  ('demo-gulab-jamun-v1', 'demo-baking-soda', 1, 'g', 'kitchen-default'),
  ('demo-gulab-jamun-v1', 'ghee', 30, 'g', 'kitchen-default'),
  ('demo-gulab-jamun-v1', 'sugar', 120, 'g', 'kitchen-default'),
  ('demo-gulab-jamun-v1', 'demo-cardamom', 0.5, 'g', 'kitchen-default')
ON CONFLICT DO NOTHING;

INSERT INTO recipe_steps
  (recipe_version_id, step_number, instruction, kitchen_id)
VALUES
  ('demo-grilled-vegetable-sandwich-v1', 1, 'Layer buttered bread with cheese and sliced vegetables.', 'kitchen-default'),
  ('demo-grilled-vegetable-sandwich-v1', 2, 'Grill until the bread is crisp and the cheese melts.', 'kitchen-default'),
  ('demo-grilled-vegetable-sandwich-v1', 3, 'Slice and serve immediately with chutney.', 'kitchen-default'),
  ('demo-idli-sambar-v1', 1, 'Steam the batter in idli moulds until soft and cooked through.', 'kitchen-default'),
  ('demo-idli-sambar-v1', 2, 'Cook dal with vegetables, tamarind and spices into sambar.', 'kitchen-default'),
  ('demo-idli-sambar-v1', 3, 'Temper with mustard and curry leaves, then serve with hot idlis.', 'kitchen-default'),
  ('demo-onion-uttapam-v1', 1, 'Spread a thick round of batter on a hot griddle.', 'kitchen-default'),
  ('demo-onion-uttapam-v1', 2, 'Top with onion, tomato, chilli and coriander.', 'kitchen-default'),
  ('demo-onion-uttapam-v1', 3, 'Cook both sides with oil until golden.', 'kitchen-default'),
  ('demo-vegetable-upma-v1', 1, 'Dry roast semolina until aromatic.', 'kitchen-default'),
  ('demo-vegetable-upma-v1', 2, 'Temper spices and saute the vegetables.', 'kitchen-default'),
  ('demo-vegetable-upma-v1', 3, 'Add semolina and water, then cook until fluffy.', 'kitchen-default'),
  ('demo-poha-v1', 1, 'Rinse and drain flattened rice until just tender.', 'kitchen-default'),
  ('demo-poha-v1', 2, 'Temper mustard, curry leaves and peanuts, then saute vegetables.', 'kitchen-default'),
  ('demo-poha-v1', 3, 'Fold in poha, finish with lemon and coriander, and serve hot.', 'kitchen-default'),
  ('demo-mango-lassi-v1', 1, 'Chill all ingredients before blending.', 'kitchen-default'),
  ('demo-mango-lassi-v1', 2, 'Blend mango, curd, milk, sugar and cardamom until smooth.', 'kitchen-default'),
  ('demo-mango-lassi-v1', 3, 'Pour into a chilled glass and serve immediately.', 'kitchen-default'),
  ('demo-fresh-lime-soda-v1', 1, 'Muddle lemon juice, mint, sugar and salt.', 'kitchen-default'),
  ('demo-fresh-lime-soda-v1', 2, 'Add chilled soda water slowly.', 'kitchen-default'),
  ('demo-fresh-lime-soda-v1', 3, 'Stir once and serve over ice.', 'kitchen-default'),
  ('demo-filter-coffee-v1', 1, 'Brew a strong coffee decoction.', 'kitchen-default'),
  ('demo-filter-coffee-v1', 2, 'Heat milk and dissolve sugar.', 'kitchen-default'),
  ('demo-filter-coffee-v1', 3, 'Combine, aerate and serve hot.', 'kitchen-default'),
  ('demo-paneer-tikka-v1', 1, 'Marinate paneer and vegetables in seasoned curd.', 'kitchen-default'),
  ('demo-paneer-tikka-v1', 2, 'Skewer and roast until lightly charred.', 'kitchen-default'),
  ('demo-paneer-tikka-v1', 3, 'Finish with kasuri methi and serve hot.', 'kitchen-default'),
  ('demo-vegetable-samosa-v1', 1, 'Prepare a firm flour dough and a spiced potato-pea filling.', 'kitchen-default'),
  ('demo-vegetable-samosa-v1', 2, 'Shape, fill and seal each samosa.', 'kitchen-default'),
  ('demo-vegetable-samosa-v1', 3, 'Fry on moderate heat until crisp and golden.', 'kitchen-default'),
  ('demo-onion-pakora-v1', 1, 'Mix sliced onion with besan, chilli, coriander and spices.', 'kitchen-default'),
  ('demo-onion-pakora-v1', 2, 'Rest briefly until the batter clings to the onion.', 'kitchen-default'),
  ('demo-onion-pakora-v1', 3, 'Fry small clusters until crisp and serve with chutney.', 'kitchen-default'),
  ('demo-paneer-butter-masala-v1', 1, 'Cook onion, tomato, cashew and spices until soft, then blend.', 'kitchen-default'),
  ('demo-paneer-butter-masala-v1', 2, 'Simmer the sauce with butter, cream and paneer.', 'kitchen-default'),
  ('demo-paneer-butter-masala-v1', 3, 'Finish with kasuri methi and adjust seasoning.', 'kitchen-default'),
  ('demo-chana-masala-v1', 1, 'Cook soaked chickpeas until tender.', 'kitchen-default'),
  ('demo-chana-masala-v1', 2, 'Build an onion-tomato masala with ginger, garlic and spices.', 'kitchen-default'),
  ('demo-chana-masala-v1', 3, 'Simmer chickpeas in the masala until thick and well coated.', 'kitchen-default'),
  ('demo-dal-tadka-v1', 1, 'Cook dal with turmeric until soft.', 'kitchen-default'),
  ('demo-dal-tadka-v1', 2, 'Fold in a cooked onion and tomato masala.', 'kitchen-default'),
  ('demo-dal-tadka-v1', 3, 'Finish with a hot ghee, cumin and garlic tempering.', 'kitchen-default'),
  ('demo-palak-paneer-v1', 1, 'Blanch spinach and blend to a smooth puree.', 'kitchen-default'),
  ('demo-palak-paneer-v1', 2, 'Cook aromatics and spices, then add the spinach puree.', 'kitchen-default'),
  ('demo-palak-paneer-v1', 3, 'Simmer with paneer and cream until glossy.', 'kitchen-default'),
  ('demo-vegetable-biryani-v1', 1, 'Par-cook rice and prepare the spiced vegetable masala.', 'kitchen-default'),
  ('demo-vegetable-biryani-v1', 2, 'Layer rice, vegetables, mint and ghee.', 'kitchen-default'),
  ('demo-vegetable-biryani-v1', 3, 'Steam covered until fragrant and the rice is fully cooked.', 'kitchen-default'),
  ('demo-butter-chicken-v1', 1, 'Marinate chicken in curd, ginger, garlic and spices, then roast.', 'kitchen-default'),
  ('demo-butter-chicken-v1', 2, 'Blend and simmer the tomato-cashew sauce with butter.', 'kitchen-default'),
  ('demo-butter-chicken-v1', 3, 'Add chicken and cream, finish with kasuri methi, and serve.', 'kitchen-default'),
  ('demo-rajma-rice-v1', 1, 'Cook soaked kidney beans until completely tender.', 'kitchen-default'),
  ('demo-rajma-rice-v1', 2, 'Simmer beans in a spiced onion-tomato gravy.', 'kitchen-default'),
  ('demo-rajma-rice-v1', 3, 'Serve the finished rajma over steamed rice.', 'kitchen-default'),
  ('demo-rice-kheer-v1', 1, 'Slow-cook rice in milk until creamy.', 'kitchen-default'),
  ('demo-rice-kheer-v1', 2, 'Add sugar, cardamom, cashews and raisins.', 'kitchen-default'),
  ('demo-rice-kheer-v1', 3, 'Finish with ghee and serve warm or chilled.', 'kitchen-default'),
  ('demo-gulab-jamun-v1', 1, 'Mix milk powder, flour and baking soda into a soft dough.', 'kitchen-default'),
  ('demo-gulab-jamun-v1', 2, 'Shape and fry gently in ghee until evenly browned.', 'kitchen-default'),
  ('demo-gulab-jamun-v1', 3, 'Soak in warm cardamom sugar syrup before serving.', 'kitchen-default')
ON CONFLICT DO NOTHING;

-- -----------------------------------------------------------------------------
-- Fresh inventory with purchase provenance
-- -----------------------------------------------------------------------------

INSERT INTO stock_lots
  (id, ingredient_id, quantity_remaining, unit, purchased_at, expires_at,
   source, kitchen_id, location_id, status, expiry_provenance,
   source_quantity, source_unit)
VALUES
  ('lot-full-001-paneer', 'paneer', 6000, 'g', CURRENT_DATE - 1, CURRENT_DATE + 5, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 6000, 'g'),
  ('lot-full-002-bread', 'bread', 4800, 'g', CURRENT_DATE - 1, CURRENT_DATE + 3, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 4800, 'g'),
  ('lot-full-003-butter', 'butter', 3000, 'g', CURRENT_DATE - 2, CURRENT_DATE + 60, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 3000, 'g'),
  ('lot-full-004-tomatoes', 'tomatoes', 9000, 'g', CURRENT_DATE - 1, CURRENT_DATE + 5, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 9000, 'g'),
  ('lot-full-005-sugar', 'sugar', 12000, 'g', CURRENT_DATE - 2, CURRENT_DATE + 180, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 12000, 'g'),
  ('lot-full-006-milk', 'milk', 16000, 'ml', CURRENT_DATE - 1, CURRENT_DATE + 4, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 16000, 'ml'),
  ('lot-full-007-dosa-batter', 'dosa-batter', 8000, 'g', CURRENT_DATE - 1, CURRENT_DATE + 5, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 8000, 'g'),
  ('lot-full-008-potatoes', 'potatoes', 14000, 'g', CURRENT_DATE - 2, CURRENT_DATE + 18, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 14000, 'g'),
  ('lot-full-009-onions', 'onions', 12000, 'g', CURRENT_DATE - 2, CURRENT_DATE + 20, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 12000, 'g'),
  ('lot-full-010-rice', 'rice', 25000, 'g', CURRENT_DATE - 2, CURRENT_DATE + 180, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 25000, 'g'),
  ('lot-full-011-atta', 'atta', 18000, 'g', CURRENT_DATE - 2, CURRENT_DATE + 120, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 18000, 'g'),
  ('lot-full-012-toor-dal', 'toor-dal', 8000, 'g', CURRENT_DATE - 2, CURRENT_DATE + 180, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 8000, 'g'),
  ('lot-full-013-chickpeas', 'chickpeas', 7000, 'g', CURRENT_DATE - 2, CURRENT_DATE + 180, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 7000, 'g'),
  ('lot-full-014-curd', 'curd', 8000, 'g', CURRENT_DATE - 1, CURRENT_DATE + 6, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 8000, 'g'),
  ('lot-full-015-coriander', 'coriander', 1000, 'g', CURRENT_DATE - 1, CURRENT_DATE + 3, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 1000, 'g'),
  ('lot-full-016-chilli-powder', 'chilli-powder', 1500, 'g', CURRENT_DATE - 2, CURRENT_DATE + 240, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 1500, 'g'),
  ('lot-full-017-turmeric', 'turmeric', 1500, 'g', CURRENT_DATE - 2, CURRENT_DATE + 300, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 1500, 'g'),
  ('lot-full-018-cumin', 'cumin', 1200, 'g', CURRENT_DATE - 2, CURRENT_DATE + 300, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 1200, 'g'),
  ('lot-full-019-garam-masala', 'garam-masala', 1200, 'g', CURRENT_DATE - 2, CURRENT_DATE + 180, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 1200, 'g'),
  ('lot-full-020-besan', 'besan', 7000, 'g', CURRENT_DATE - 2, CURRENT_DATE + 150, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 7000, 'g'),
  ('lot-full-021-maida', 'maida', 8000, 'g', CURRENT_DATE - 2, CURRENT_DATE + 150, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 8000, 'g'),
  ('lot-full-022-ghee', 'ghee', 4000, 'g', CURRENT_DATE - 2, CURRENT_DATE + 240, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 4000, 'g'),
  ('lot-full-023-cooking-oil', 'cooking-oil', 15000, 'ml', CURRENT_DATE - 2, CURRENT_DATE + 240, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 15000, 'ml'),
  ('lot-full-024-ginger', 'ginger', 1800, 'g', CURRENT_DATE - 1, CURRENT_DATE + 14, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 1800, 'g'),
  ('lot-full-025-garlic', 'garlic', 2200, 'g', CURRENT_DATE - 1, CURRENT_DATE + 25, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 2200, 'g'),
  ('lot-full-026-green-chillies', 'green-chillies', 1200, 'g', CURRENT_DATE - 1, CURRENT_DATE + 7, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 1200, 'g'),
  ('lot-full-027-mustard-seeds', 'mustard-seeds', 1000, 'g', CURRENT_DATE - 2, CURRENT_DATE + 300, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 1000, 'g'),
  ('lot-full-028-capsicum', 'demo-capsicum', 3500, 'g', CURRENT_DATE - 1, CURRENT_DATE + 6, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 3500, 'g'),
  ('lot-full-029-green-peas', 'demo-green-peas', 5000, 'g', CURRENT_DATE - 1, CURRENT_DATE + 45, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 5000, 'g'),
  ('lot-full-030-carrots', 'demo-carrots', 5000, 'g', CURRENT_DATE - 1, CURRENT_DATE + 12, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 5000, 'g'),
  ('lot-full-031-cauliflower', 'demo-cauliflower', 4000, 'g', CURRENT_DATE - 1, CURRENT_DATE + 7, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 4000, 'g'),
  ('lot-full-032-spinach', 'demo-spinach', 3500, 'g', CURRENT_DATE - 1, CURRENT_DATE + 3, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 3500, 'g'),
  ('lot-full-033-cucumber', 'demo-cucumber', 3000, 'g', CURRENT_DATE - 1, CURRENT_DATE + 5, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 3000, 'g'),
  ('lot-full-034-lemon', 'demo-lemon', 100, 'each', CURRENT_DATE - 1, CURRENT_DATE + 18, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 100, 'each'),
  ('lot-full-035-mint', 'demo-mint', 700, 'g', CURRENT_DATE - 1, CURRENT_DATE + 3, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 700, 'g'),
  ('lot-full-036-curry-leaves', 'demo-curry-leaves', 500, 'g', CURRENT_DATE - 1, CURRENT_DATE + 4, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 500, 'g'),
  ('lot-full-037-coconut', 'demo-coconut', 2400, 'g', CURRENT_DATE - 1, CURRENT_DATE + 6, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 2400, 'g'),
  ('lot-full-038-tamarind', 'demo-tamarind', 2000, 'g', CURRENT_DATE - 2, CURRENT_DATE + 180, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 2000, 'g'),
  ('lot-full-039-kidney-beans', 'demo-kidney-beans', 7000, 'g', CURRENT_DATE - 2, CURRENT_DATE + 180, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 7000, 'g'),
  ('lot-full-040-semolina', 'demo-semolina', 6000, 'g', CURRENT_DATE - 2, CURRENT_DATE + 180, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 6000, 'g'),
  ('lot-full-041-flattened-rice', 'demo-flattened-rice', 6000, 'g', CURRENT_DATE - 2, CURRENT_DATE + 150, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 6000, 'g'),
  ('lot-full-042-peanuts', 'demo-peanuts', 2500, 'g', CURRENT_DATE - 2, CURRENT_DATE + 180, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 2500, 'g'),
  ('lot-full-043-cashews', 'demo-cashews', 1800, 'g', CURRENT_DATE - 2, CURRENT_DATE + 180, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 1800, 'g'),
  ('lot-full-044-raisins', 'demo-raisins', 1800, 'g', CURRENT_DATE - 2, CURRENT_DATE + 240, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 1800, 'g'),
  ('lot-full-045-fresh-cream', 'demo-fresh-cream', 5000, 'ml', CURRENT_DATE - 1, CURRENT_DATE + 8, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 5000, 'ml'),
  ('lot-full-046-cheese', 'demo-cheese', 4000, 'g', CURRENT_DATE - 1, CURRENT_DATE + 25, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 4000, 'g'),
  ('lot-full-047-chicken', 'demo-chicken', 8000, 'g', CURRENT_DATE - 1, CURRENT_DATE + 3, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 8000, 'g'),
  ('lot-full-048-mango-pulp', 'demo-mango-pulp', 5000, 'g', CURRENT_DATE - 1, CURRENT_DATE + 60, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 5000, 'g'),
  ('lot-full-049-coffee', 'demo-coffee', 2500, 'g', CURRENT_DATE - 2, CURRENT_DATE + 300, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 2500, 'g'),
  ('lot-full-050-cardamom', 'demo-cardamom', 600, 'g', CURRENT_DATE - 2, CURRENT_DATE + 360, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 600, 'g'),
  ('lot-full-051-cinnamon', 'demo-cinnamon', 500, 'g', CURRENT_DATE - 2, CURRENT_DATE + 360, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 500, 'g'),
  ('lot-full-052-cloves', 'demo-cloves', 400, 'g', CURRENT_DATE - 2, CURRENT_DATE + 360, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 400, 'g'),
  ('lot-full-053-black-pepper', 'demo-black-pepper', 800, 'g', CURRENT_DATE - 2, CURRENT_DATE + 360, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 800, 'g'),
  ('lot-full-054-coriander-powder', 'demo-coriander-powder', 1500, 'g', CURRENT_DATE - 2, CURRENT_DATE + 240, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 1500, 'g'),
  ('lot-full-055-kasuri-methi', 'demo-kasuri-methi', 500, 'g', CURRENT_DATE - 2, CURRENT_DATE + 240, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 500, 'g'),
  ('lot-full-056-chaat-masala', 'demo-chaat-masala', 1000, 'g', CURRENT_DATE - 2, CURRENT_DATE + 240, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 1000, 'g'),
  ('lot-full-057-baking-soda', 'demo-baking-soda', 1000, 'g', CURRENT_DATE - 2, CURRENT_DATE + 365, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 1000, 'g'),
  ('lot-full-058-soda-water', 'demo-soda-water', 12000, 'ml', CURRENT_DATE - 1, CURRENT_DATE + 240, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 12000, 'ml'),
  ('lot-full-059-idli-batter', 'demo-idli-batter', 9000, 'g', CURRENT_DATE - 1, CURRENT_DATE + 4, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 9000, 'g'),
  ('lot-full-060-milk-powder', 'demo-milk-powder', 4500, 'g', CURRENT_DATE - 2, CURRENT_DATE + 240, 'synthetic-restaurant-seed', 'kitchen-default', 'location-main', 'AVAILABLE', 'PRINTED_DATE', 4500, 'g')
ON CONFLICT DO NOTHING;

INSERT INTO stock_movements
  (id, stock_lot_id, ingredient_id, movement_type, quantity_change, unit,
   reference_type, reference_id, occurred_at, kitchen_id, location_id)
SELECT
  CONCAT('movement-', lot.id),
  lot.id,
  lot.ingredient_id,
  'PURCHASE',
  lot.source_quantity,
  lot.source_unit,
  'SYNTHETIC_SEED',
  lot.id,
  CURRENT_TIMESTAMP,
  lot.kitchen_id,
  lot.location_id
FROM stock_lots lot
WHERE lot.source = 'synthetic-restaurant-seed'
  AND lot.kitchen_id = 'kitchen-default'
  AND lot.location_id = 'location-main'
ON CONFLICT DO NOTHING;

-- -----------------------------------------------------------------------------
-- Recent, varied customer feedback across the live menu
-- -----------------------------------------------------------------------------

INSERT INTO feedback
  (id, recipe_id, feedback_text, rating, occurred_at, source, kitchen_id)
VALUES
  ('FB-FULL-001', 'demo-grilled-vegetable-sandwich-v1', 'Fresh vegetables and melted cheese made this very satisfying.', 5, CURRENT_DATE - 2, 'qr', 'kitchen-default'),
  ('FB-FULL-002', 'demo-grilled-vegetable-sandwich-v1', 'Tasty filling, but the bread could have been crisper.', 4, CURRENT_DATE - 9, 'counter', 'kitchen-default'),
  ('FB-FULL-003', 'demo-grilled-vegetable-sandwich-v1', 'Good portion; a little more chutney would be welcome.', 4, CURRENT_DATE - 18, 'dine-in', 'kitchen-default'),

  ('FB-FULL-004', 'demo-idli-sambar-v1', 'The idlis were soft and the sambar tasted homestyle.', 5, CURRENT_DATE - 1, 'dine-in', 'kitchen-default'),
  ('FB-FULL-005', 'demo-idli-sambar-v1', 'Sambar needed a little more tang for my taste.', 3, CURRENT_DATE - 8, 'qr', 'kitchen-default'),
  ('FB-FULL-006', 'demo-idli-sambar-v1', 'A comforting breakfast and it arrived piping hot.', 5, CURRENT_DATE - 20, 'delivery', 'kitchen-default'),

  ('FB-FULL-007', 'demo-onion-uttapam-v1', 'Crisp outside, fluffy centre, and plenty of onion.', 5, CURRENT_DATE - 3, 'counter', 'kitchen-default'),
  ('FB-FULL-008', 'demo-onion-uttapam-v1', 'The toppings were good but the centre felt slightly heavy.', 3, CURRENT_DATE - 12, 'qr', 'kitchen-default'),
  ('FB-FULL-009', 'demo-onion-uttapam-v1', 'Nice flavour and the chilli level was just right.', 4, CURRENT_DATE - 25, 'dine-in', 'kitchen-default'),

  ('FB-FULL-010', 'demo-vegetable-upma-v1', 'Fluffy upma with a generous amount of vegetables.', 5, CURRENT_DATE - 4, 'dine-in', 'kitchen-default'),
  ('FB-FULL-011', 'demo-vegetable-upma-v1', 'The texture was good, though this serving was too salty.', 2, CURRENT_DATE - 13, 'qr', 'kitchen-default'),
  ('FB-FULL-012', 'demo-vegetable-upma-v1', 'Warm, fresh and ideal for a light breakfast.', 4, CURRENT_DATE - 28, 'counter', 'kitchen-default'),

  ('FB-FULL-013', 'demo-poha-v1', 'Bright lemon flavour and a lovely crunch from the peanuts.', 5, CURRENT_DATE - 2, 'qr', 'kitchen-default'),
  ('FB-FULL-014', 'demo-poha-v1', 'Good seasoning but the poha was a little dry.', 3, CURRENT_DATE - 11, 'delivery', 'kitchen-default'),
  ('FB-FULL-015', 'demo-poha-v1', 'Fresh coriander and soft potatoes made it feel homemade.', 4, CURRENT_DATE - 24, 'dine-in', 'kitchen-default'),

  ('FB-FULL-016', 'demo-mango-lassi-v1', 'Thick, cold and full of real mango flavour.', 5, CURRENT_DATE - 1, 'counter', 'kitchen-default'),
  ('FB-FULL-017', 'demo-mango-lassi-v1', 'Creamy and refreshing, although slightly too sweet.', 3, CURRENT_DATE - 10, 'qr', 'kitchen-default'),
  ('FB-FULL-018', 'demo-mango-lassi-v1', 'The cardamom finish was subtle and delicious.', 5, CURRENT_DATE - 21, 'dine-in', 'kitchen-default'),

  ('FB-FULL-019', 'demo-fresh-lime-soda-v1', 'Very refreshing with a clean sweet-salty balance.', 5, CURRENT_DATE - 5, 'dine-in', 'kitchen-default'),
  ('FB-FULL-020', 'demo-fresh-lime-soda-v1', 'The mint was fresh but the soda had gone a little flat.', 2, CURRENT_DATE - 14, 'qr', 'kitchen-default'),
  ('FB-FULL-021', 'demo-fresh-lime-soda-v1', 'Sharp, fizzy and perfect with the pakoras.', 5, CURRENT_DATE - 29, 'counter', 'kitchen-default'),

  ('FB-FULL-022', 'demo-filter-coffee-v1', 'Strong decoction and excellent aroma.', 5, CURRENT_DATE - 3, 'counter', 'kitchen-default'),
  ('FB-FULL-023', 'demo-filter-coffee-v1', 'Smooth coffee, but I would prefer a little less milk.', 3, CURRENT_DATE - 17, 'qr', 'kitchen-default'),
  ('FB-FULL-024', 'demo-filter-coffee-v1', 'Hot, balanced and not overly sweet.', 4, CURRENT_DATE - 31, 'dine-in', 'kitchen-default'),

  ('FB-FULL-025', 'demo-paneer-tikka-v1', 'Beautiful smoky edges and soft paneer.', 5, CURRENT_DATE - 2, 'dine-in', 'kitchen-default'),
  ('FB-FULL-026', 'demo-paneer-tikka-v1', 'The marinade was flavourful but a few cubes were chewy.', 3, CURRENT_DATE - 15, 'qr', 'kitchen-default'),
  ('FB-FULL-027', 'demo-paneer-tikka-v1', 'Generous portion with well-roasted peppers and onions.', 5, CURRENT_DATE - 33, 'delivery', 'kitchen-default'),

  ('FB-FULL-028', 'demo-vegetable-samosa-v1', 'Crisp shell and a nicely spiced potato filling.', 5, CURRENT_DATE - 4, 'counter', 'kitchen-default'),
  ('FB-FULL-029', 'demo-vegetable-samosa-v1', 'Good filling, although the pastry tasted a little oily.', 3, CURRENT_DATE - 16, 'qr', 'kitchen-default'),
  ('FB-FULL-030', 'demo-vegetable-samosa-v1', 'Still crunchy when the delivery reached us.', 4, CURRENT_DATE - 35, 'delivery', 'kitchen-default'),

  ('FB-FULL-031', 'demo-onion-pakora-v1', 'Light, crisp pakoras with plenty of onion.', 5, CURRENT_DATE - 6, 'dine-in', 'kitchen-default'),
  ('FB-FULL-032', 'demo-onion-pakora-v1', 'The flavour was good but the last pieces were soft.', 2, CURRENT_DATE - 19, 'delivery', 'kitchen-default'),
  ('FB-FULL-033', 'demo-onion-pakora-v1', 'Chaat masala gave these a great finish.', 4, CURRENT_DATE - 37, 'qr', 'kitchen-default'),

  ('FB-FULL-034', 'demo-paneer-butter-masala-v1', 'Rich, silky gravy and very soft paneer.', 5, CURRENT_DATE - 1, 'dine-in', 'kitchen-default'),
  ('FB-FULL-035', 'demo-paneer-butter-masala-v1', 'Creamy and comforting, though the sauce leaned too sweet.', 3, CURRENT_DATE - 12, 'qr', 'kitchen-default'),
  ('FB-FULL-036', 'demo-paneer-butter-masala-v1', 'A generous main with a deep tomato flavour.', 5, CURRENT_DATE - 26, 'delivery', 'kitchen-default'),

  ('FB-FULL-037', 'demo-chana-masala-v1', 'Tender chickpeas and a robust masala.', 5, CURRENT_DATE - 5, 'counter', 'kitchen-default'),
  ('FB-FULL-038', 'demo-chana-masala-v1', 'Well cooked and filling; I would enjoy a little more heat.', 4, CURRENT_DATE - 18, 'qr', 'kitchen-default'),
  ('FB-FULL-039', 'demo-chana-masala-v1', 'The gravy travelled well and stayed nicely thick.', 4, CURRENT_DATE - 39, 'delivery', 'kitchen-default'),

  ('FB-FULL-040', 'demo-dal-tadka-v1', 'The garlic and cumin tempering smelled wonderful.', 5, CURRENT_DATE - 3, 'dine-in', 'kitchen-default'),
  ('FB-FULL-041', 'demo-dal-tadka-v1', 'Comforting flavour, but the dal was a touch thin.', 3, CURRENT_DATE - 14, 'qr', 'kitchen-default'),
  ('FB-FULL-042', 'demo-dal-tadka-v1', 'Simple, hot and exactly what I wanted with rice.', 5, CURRENT_DATE - 30, 'counter', 'kitchen-default'),

  ('FB-FULL-043', 'demo-palak-paneer-v1', 'Fresh spinach flavour with tender paneer pieces.', 5, CURRENT_DATE - 7, 'dine-in', 'kitchen-default'),
  ('FB-FULL-044', 'demo-palak-paneer-v1', 'Creamy texture, although the spinach had a slightly bitter note.', 3, CURRENT_DATE - 20, 'qr', 'kitchen-default'),
  ('FB-FULL-045', 'demo-palak-paneer-v1', 'Balanced spices and a good portion for one person.', 4, CURRENT_DATE - 41, 'delivery', 'kitchen-default'),

  ('FB-FULL-046', 'demo-vegetable-biryani-v1', 'Fragrant rice with vegetables in every spoonful.', 5, CURRENT_DATE - 2, 'counter', 'kitchen-default'),
  ('FB-FULL-047', 'demo-vegetable-biryani-v1', 'Lovely whole-spice aroma; a few more vegetables would be nice.', 4, CURRENT_DATE - 13, 'qr', 'kitchen-default'),
  ('FB-FULL-048', 'demo-vegetable-biryani-v1', 'Tasty biryani, but the sealed delivery box softened the rice.', 3, CURRENT_DATE - 27, 'delivery', 'kitchen-default'),

  ('FB-FULL-049', 'demo-butter-chicken-v1', 'The sauce was excellent and the chicken stayed juicy.', 5, CURRENT_DATE - 4, 'dine-in', 'kitchen-default'),
  ('FB-FULL-050', 'demo-butter-chicken-v1', 'Tender chicken and a satisfying portion.', 4, CURRENT_DATE - 16, 'delivery', 'kitchen-default'),
  ('FB-FULL-051', 'demo-butter-chicken-v1', 'Good texture, though the gravy was sweeter than expected.', 3, CURRENT_DATE - 34, 'qr', 'kitchen-default'),

  ('FB-FULL-052', 'demo-rajma-rice-v1', 'Comforting rajma with a deep, slow-cooked flavour.', 5, CURRENT_DATE - 6, 'counter', 'kitchen-default'),
  ('FB-FULL-053', 'demo-rajma-rice-v1', 'Nicely seasoned; the beans could be slightly softer.', 4, CURRENT_DATE - 22, 'qr', 'kitchen-default'),
  ('FB-FULL-054', 'demo-rajma-rice-v1', 'The rajma was good but the delivered rice felt dry.', 3, CURRENT_DATE - 43, 'delivery', 'kitchen-default'),

  ('FB-FULL-055', 'demo-rice-kheer-v1', 'Creamy kheer with a beautifully balanced cardamom flavour.', 5, CURRENT_DATE - 5, 'dine-in', 'kitchen-default'),
  ('FB-FULL-056', 'demo-rice-kheer-v1', 'Nice texture, but it was sweeter than I prefer.', 3, CURRENT_DATE - 19, 'qr', 'kitchen-default'),
  ('FB-FULL-057', 'demo-rice-kheer-v1', 'Delicious chilled dessert; the portion could be larger.', 4, CURRENT_DATE - 38, 'delivery', 'kitchen-default'),

  ('FB-FULL-058', 'demo-gulab-jamun-v1', 'Soft centres and served at just the right temperature.', 5, CURRENT_DATE - 3, 'counter', 'kitchen-default'),
  ('FB-FULL-059', 'demo-gulab-jamun-v1', 'Fresh and tender, although the syrup was very sweet.', 3, CURRENT_DATE - 23, 'qr', 'kitchen-default'),
  ('FB-FULL-060', 'demo-gulab-jamun-v1', 'Warm gulab jamun made a lovely finish to the meal.', 5, CURRENT_DATE - 42, 'dine-in', 'kitchen-default'),

  ('FB-FULL-061', 'masala-dosa-v1', 'Excellent crisp dosa with a well-seasoned potato filling.', 5, CURRENT_DATE - 2, 'dine-in', 'kitchen-default'),
  ('FB-FULL-062', 'masala-dosa-v1', 'The filling was tasty but the dosa softened during delivery.', 3, CURRENT_DATE - 15, 'delivery', 'kitchen-default'),
  ('FB-FULL-063', 'masala-dosa-v1', 'Good size and nicely crisp around the edges.', 4, CURRENT_DATE - 32, 'qr', 'kitchen-default'),
  ('FB-FULL-064', 'masala-dosa-v1', 'Satisfying breakfast; a little more potato would make it perfect.', 4, CURRENT_DATE - 47, 'counter', 'kitchen-default'),

  ('FB-FULL-065', 'chai-v1', 'Properly hot chai with a strong tea flavour.', 5, CURRENT_DATE - 1, 'counter', 'kitchen-default'),
  ('FB-FULL-066', 'chai-v1', 'Comforting cup, but it was a little too sweet.', 3, CURRENT_DATE - 10, 'qr', 'kitchen-default'),
  ('FB-FULL-067', 'chai-v1', 'Milky, aromatic and exactly right with a samosa.', 5, CURRENT_DATE - 29, 'dine-in', 'kitchen-default'),
  ('FB-FULL-068', 'chai-v1', 'Good chai, although it could be served slightly hotter.', 4, CURRENT_DATE - 45, 'delivery', 'kitchen-default'),

  ('FB-FULL-069', 'paneer-sandwich-v1', 'The fresh tomato and extra filling made this much better.', 4, CURRENT_DATE - 6, 'qr', 'kitchen-default'),
  ('FB-FULL-070', 'paneer-sandwich-v1', 'Nicely grilled and moist, with a generous paneer layer.', 5, CURRENT_DATE - 36, 'counter', 'kitchen-default')
ON CONFLICT DO NOTHING;

