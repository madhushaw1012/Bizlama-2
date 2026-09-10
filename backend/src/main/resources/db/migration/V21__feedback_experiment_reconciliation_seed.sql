-- Give the expanded demo menu several genuine repeated feedback themes so the
-- deterministic experiment reconciler can materialize proposals from evidence.
-- Existing operator rows remain untouched.

-- V20 predates explicit population of the single-active-recipe guard. Repair
-- only those synthetic rows; operator activation may already have superseded
-- any of them, in which case active = FALSE leaves the row alone.
UPDATE recipe_versions
SET active_dish_guard = dish_id
WHERE kitchen_id = 'kitchen-default'
  AND id LIKE 'demo-%'
  AND active = TRUE
  AND active_dish_guard IS NULL;

INSERT INTO feedback
  (id, recipe_id, feedback_text, rating, occurred_at, source, kitchen_id)
VALUES
  ('FB-EXPERIMENT-001', 'demo-vegetable-upma-v1',
   'Tasty overall, but this batch of upma was too salty.',
   3, CURRENT_DATE - 3, 'qr', 'kitchen-default'),
  ('FB-EXPERIMENT-002', 'demo-vegetable-upma-v1',
   'There was too much salt in the upma today.',
   2, CURRENT_DATE - 1, 'counter', 'kitchen-default'),
  ('FB-EXPERIMENT-003', 'demo-mango-lassi-v1',
   'The mango lassi was too sweet for me.',
   3, CURRENT_DATE - 4, 'dine-in', 'kitchen-default'),
  ('FB-EXPERIMENT-004', 'demo-mango-lassi-v1',
   'Please use less sugar; this tasted too sweet.',
   2, CURRENT_DATE - 1, 'qr', 'kitchen-default'),
  ('FB-EXPERIMENT-005', 'demo-butter-chicken-v1',
   'The butter chicken sauce was too sweet today.',
   3, CURRENT_DATE - 5, 'delivery', 'kitchen-default'),
  ('FB-EXPERIMENT-006', 'demo-butter-chicken-v1',
   'Too much sugar overpowered the tomato flavour.',
   2, CURRENT_DATE - 2, 'qr', 'kitchen-default'),
  ('FB-EXPERIMENT-007', 'chai-v1',
   'The chai was too sweet and needed less sugar.',
   3, CURRENT_DATE - 4, 'counter', 'kitchen-default'),
  ('FB-EXPERIMENT-008', 'chai-v1',
   'Too much sugar masked the tea flavour.',
   2, CURRENT_DATE - 1, 'qr', 'kitchen-default')
ON CONFLICT DO NOTHING;
