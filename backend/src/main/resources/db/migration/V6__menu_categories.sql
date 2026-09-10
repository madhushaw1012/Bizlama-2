INSERT INTO catalog_categories (id, kitchen_id, name) VALUES
  ('category-sandwiches', 'kitchen-default', 'Sandwiches'),
  ('category-south-indian', 'kitchen-default', 'South Indian'),
  ('category-beverages', 'kitchen-default', 'Beverages'),
  ('category-snacks', 'kitchen-default', 'Snacks'),
  ('category-main-course', 'kitchen-default', 'Main course'),
  ('category-desserts', 'kitchen-default', 'Desserts');


UPDATE dishes SET category_id = 'category-sandwiches' WHERE id = 'paneer-sandwich';
UPDATE dishes SET category_id = 'category-south-indian' WHERE id = 'masala-dosa';
UPDATE dishes SET category_id = 'category-beverages' WHERE id = 'chai';