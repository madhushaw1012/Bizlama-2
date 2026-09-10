-- SYNTHETIC SQL FIXTURE: live starts with duplicate fact/feature keys while
-- exact-replay staging contains one authoritative row per key.
CREATE TEMP TABLE duplicate_live_facts AS
SELECT 'duplicate-event' event_id, DATE '2026-09-09' partition_date UNION ALL
SELECT 'duplicate-event', DATE '2026-09-09';

CREATE TEMP TABLE exact_fact_staging AS
SELECT 'duplicate-event' event_id, DATE '2026-09-09' partition_date;

CREATE TEMP TABLE duplicate_live_features AS
SELECT '1h' window_name, TIMESTAMP '2026-09-09 10:00:00+00' window_start,
       'kitchen-a' kitchen_id, 'rice' ingredient_id UNION ALL
SELECT '1h', TIMESTAMP '2026-09-09 10:00:00+00', 'kitchen-a', 'rice';

CREATE TEMP TABLE exact_feature_staging AS
SELECT '1h' window_name, TIMESTAMP '2026-09-09 10:00:00+00' window_start,
       'kitchen-a' kitchen_id, 'rice' ingredient_id;
