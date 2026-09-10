INSERT INTO ingredients (id, name, base_unit) VALUES
  ('paneer', 'Paneer', 'g'),
  ('bread', 'Bread', 'g'),
  ('butter', 'Butter', 'g'),
  ('tomatoes', 'Tomatoes', 'g'),
  ('sugar', 'Sugar', 'g'),
  ('milk', 'Milk', 'ml'),
  ('tea-leaves', 'Tea leaves', 'g'),
  ('dosa-batter', 'Dosa batter', 'g'),
  ('potatoes', 'Potatoes', 'g'),
  ('onions', 'Onions', 'g'),
  ('rice', 'Rice', 'g'),
  ('atta', 'Whole wheat flour', 'g'),
  ('toor-dal', 'Toor dal', 'g'),
  ('chickpeas', 'Chickpeas', 'g'),
  ('curd', 'Curd', 'g'),
  ('coriander', 'Coriander', 'g'),
  ('chilli-powder', 'Chilli powder', 'g'),
  ('turmeric', 'Turmeric', 'g'),
  ('cumin', 'Cumin', 'g'),
  ('garam-masala', 'Garam masala', 'g');


INSERT INTO ingredient_aliases (
  alias_normalized,
  ingredient_id,
  display_name,
  brand,
  confidence,
  source
) VALUES
  ('paneer', 'paneer', 'Paneer', NULL, 1.0000, 'catalog'),
  ('amul paneer', 'paneer', 'Amul Paneer', 'Amul', 0.9900, 'curated'),
  ('amul fresh paneer', 'paneer', 'Amul Fresh Paneer', 'Amul', 0.9900, 'curated'),
  ('heritage high protein paneer', 'paneer', 'Heritage High Protein Paneer', 'Heritage', 0.9900, 'curated'),
  ('high protein heritage paneer', 'paneer', 'High Protein Heritage Paneer', 'Heritage', 0.9900, 'curated'),
  ('cottage cheese', 'paneer', 'Cottage cheese', NULL, 0.9200, 'curated'),
  ('tomato', 'tomatoes', 'Tomato', NULL, 0.9900, 'curated'),
  ('tomatoes', 'tomatoes', 'Tomatoes', NULL, 1.0000, 'catalog'),
  ('bread', 'bread', 'Bread', NULL, 1.0000, 'catalog'),
  ('butter', 'butter', 'Butter', NULL, 1.0000, 'catalog'),
  ('dosa batter', 'dosa-batter', 'Dosa batter', NULL, 1.0000, 'catalog'),
  ('sugar', 'sugar', 'Sugar', NULL, 1.0000, 'catalog'),
  ('potato', 'potatoes', 'Potato', NULL, 0.9900, 'curated'),
  ('potatoes', 'potatoes', 'Potatoes', NULL, 1.0000, 'catalog'),
  ('onion', 'onions', 'Onion', NULL, 0.9900, 'curated'),
  ('onions', 'onions', 'Onions', NULL, 1.0000, 'catalog'),
  ('rice', 'rice', 'Rice', NULL, 1.0000, 'catalog'),
  ('amul butter', 'butter', 'Amul Butter', 'Amul', 0.9900, 'curated'),
  ('taaza milk', 'milk', 'Taaza Milk', 'Amul', 0.9700, 'curated'),
  ('milk', 'milk', 'Milk', NULL, 1.0000, 'catalog'),
  ('chai patti', 'tea-leaves', 'Chai patti', NULL, 0.9900, 'curated'),
  ('tea leaves', 'tea-leaves', 'Tea leaves', NULL, 1.0000, 'catalog'),
  ('aashirvaad atta', 'atta', 'Aashirvaad Atta', 'Aashirvaad', 0.9900, 'curated'),
  ('toor dal', 'toor-dal', 'Toor dal', NULL, 1.0000, 'catalog'),
  ('whole wheat flour', 'atta', 'Whole wheat flour', NULL, 0.9800, 'curated'),
  ('chickpeas', 'chickpeas', 'Chickpeas', NULL, 1.0000, 'catalog'),
  ('coriander', 'coriander', 'Coriander', NULL, 1.0000, 'catalog'),
  ('chilli powder', 'chilli-powder', 'Chilli powder', NULL, 1.0000, 'catalog'),
  ('turmeric', 'turmeric', 'Turmeric', NULL, 1.0000, 'catalog'),
  ('cumin', 'cumin', 'Cumin', NULL, 1.0000, 'catalog'),
  ('garam masala', 'garam-masala', 'Garam masala', NULL, 1.0000, 'catalog'),
  ('dahi', 'curd', 'Dahi', NULL, 0.9900, 'curated'),
  ('yogurt', 'curd', 'Yogurt', NULL, 0.9500, 'curated');


INSERT INTO dishes (id, name, price, active) VALUES
  ('paneer-sandwich', 'Paneer Sandwich', 120.00, TRUE),
  ('masala-dosa', 'Masala Dosa', 110.00, TRUE),
  ('chai', 'Chai', 35.00, TRUE);


INSERT INTO recipe_versions (
  id,
  dish_id,
  version_number,
  change_reason,
  active
) VALUES
  ('paneer-sandwich-v1', 'paneer-sandwich', 1, 'Initial recipe', TRUE),
  ('masala-dosa-v1', 'masala-dosa', 1, 'Initial recipe', TRUE),
  ('chai-v1', 'chai', 1, 'Initial recipe', TRUE);


UPDATE dishes
SET active_recipe_version_id = 'paneer-sandwich-v1'
WHERE id = 'paneer-sandwich';

UPDATE dishes
SET active_recipe_version_id = 'masala-dosa-v1'
WHERE id = 'masala-dosa';

UPDATE dishes
SET active_recipe_version_id = 'chai-v1'
WHERE id = 'chai';


INSERT INTO recipe_ingredients (
  recipe_version_id,
  ingredient_id,
  quantity,
  unit
) VALUES
  ('paneer-sandwich-v1', 'paneer', 80, 'g'),
  ('paneer-sandwich-v1', 'bread', 120, 'g'),
  ('paneer-sandwich-v1', 'butter', 8, 'g'),
  ('paneer-sandwich-v1', 'tomatoes', 30, 'g'),
  ('masala-dosa-v1', 'dosa-batter', 300, 'g'),
  ('masala-dosa-v1', 'potatoes', 150, 'g'),
  ('chai-v1', 'milk', 250, 'ml'),
  ('chai-v1', 'tea-leaves', 10, 'g'),
  ('chai-v1', 'sugar', 12, 'g');


INSERT INTO recipe_steps (
  recipe_version_id,
  step_number,
  instruction
) VALUES
  ('paneer-sandwich-v1', 1, 'Butter the bread'),
  ('paneer-sandwich-v1', 2, 'Add paneer and tomato filling'),
  ('paneer-sandwich-v1', 3, 'Grill until toasted'),
  ('masala-dosa-v1', 1, 'Heat the pan'),
  ('masala-dosa-v1', 2, 'Spread batter evenly'),
  ('masala-dosa-v1', 3, 'Add potato filling and fold'),
  ('chai-v1', 1, 'Boil milk'),
  ('chai-v1', 2, 'Add tea leaves and sugar'),
  ('chai-v1', 3, 'Strain and serve');


INSERT INTO stock_lots (
  id,
  ingredient_id,
  quantity_remaining,
  unit,
  purchased_at,
  expires_at,
  source
) VALUES
  ('lot-paneer-seed', 'paneer', 1200, 'g', CURRENT_DATE, CURRENT_DATE + 1, 'demo-seed'),
  ('lot-bread-seed', 'bread', 1440, 'g', CURRENT_DATE, CURRENT_DATE + 1, 'demo-seed'),
  ('lot-butter-seed', 'butter', 420, 'g', CURRENT_DATE, CURRENT_DATE + 30, 'demo-seed'),
  ('lot-tomato-seed', 'tomatoes', 700, 'g', CURRENT_DATE, CURRENT_DATE + 3, 'demo-seed'),
  ('lot-sugar-seed', 'sugar', 2000, 'g', CURRENT_DATE, CURRENT_DATE + 180, 'demo-seed'),
  ('lot-milk-seed', 'milk', 4000, 'ml', CURRENT_DATE, CURRENT_DATE + 5, 'demo-seed'),
  ('lot-batter-seed', 'dosa-batter', 3400, 'g', CURRENT_DATE, CURRENT_DATE + 5, 'demo-seed'),
  ('lot-potato-seed', 'potatoes', 2200, 'g', CURRENT_DATE, CURRENT_DATE + 12, 'demo-seed'),
  ('lot-tea-seed', 'tea-leaves', 500, 'g', CURRENT_DATE, CURRENT_DATE + 180, 'demo-seed'),
  ('lot-onion-seed', 'onions', 1000, 'g', CURRENT_DATE, CURRENT_DATE + 10, 'demo-seed');


INSERT INTO customer_orders (
  id,
  total,
  status,
  created_at
) VALUES
  ('ORD-DEMO-001', 1200.00, 'QUEUED', CURRENT_TIMESTAMP),
  ('ORD-DEMO-002', 440.00, 'QUEUED', CURRENT_TIMESTAMP),
  ('ORD-DEMO-003', 70.00, 'QUEUED', CURRENT_TIMESTAMP);


INSERT INTO order_items (
  order_id,
  line_number,
  dish_id,
  quantity,
  unit_price
) VALUES
  ('ORD-DEMO-001', 1, 'paneer-sandwich', 10, 120.00),
  ('ORD-DEMO-002', 1, 'masala-dosa', 4, 110.00),
  ('ORD-DEMO-003', 1, 'chai', 2, 35.00);


INSERT INTO feedback (
  id,
  recipe_id,
  feedback_text,
  rating,
  occurred_at,
  source
) VALUES
  ('FB-DEMO-001', 'paneer-sandwich-v1', 'The sandwich felt dry.', 3, CURRENT_DATE, 'counter'),
  ('FB-DEMO-002', 'paneer-sandwich-v1', 'Could use more sauce.', 3, CURRENT_DATE, 'qr'),
  ('FB-DEMO-003', 'paneer-sandwich-v1', 'Bread was a bit hard.', 3, CURRENT_DATE, 'counter'),
  ('FB-DEMO-004', 'paneer-sandwich-v1', 'Paneer was good but needed moisture.', 4, CURRENT_DATE, 'qr');


INSERT INTO recipe_experiments (
  id,
  dish_id,
  theme,
  theme_count,
  feedback_count,
  current_value,
  proposed_value,
  test_duration_days,
  status
) VALUES
  ('EXP-PANEER-001', 'paneer-sandwich', 'Dry texture', 4, 12, 8, 15, 5, 'PROPOSED');


INSERT INTO activity_events (
  id,
  event_type,
  description,
  occurred_at
) VALUES
  ('EV-DEMO-001', 'Production', 'Made 10 Paneer Sandwiches', CURRENT_TIMESTAMP),
  ('EV-DEMO-002', 'Purchase', 'Bought 2kg paneer', CURRENT_TIMESTAMP),
  ('EV-DEMO-003', 'Waste', 'Recorded 300g tomato waste', CURRENT_TIMESTAMP);