-- Exact bounded replay writes to staging, never directly to live:
-- signal_facts_reconciled_staging and signal_features_reconciled_staging.
-- Bind @start_date and @end_date to the affected partitions.

ASSERT @start_date <= @end_date
  AS 'start_date must be on or before end_date';

ASSERT (
  SELECT COUNT(*) > 0
  FROM `${PROJECT_ID}.${DATASET}.signal_facts_reconciled_staging`
  WHERE DATE(occurred_at) BETWEEN @start_date AND @end_date
) AS 'fact staging must not be empty in the requested range';

ASSERT (
  SELECT COUNT(*) > 0
  FROM `${PROJECT_ID}.${DATASET}.signal_features_reconciled_staging`
  WHERE DATE(window_start) BETWEEN @start_date AND @end_date
) AS 'feature staging must not be empty in the requested range';

ASSERT (
  SELECT COUNTIF(NOT DATE(occurred_at) BETWEEN @start_date AND @end_date) = 0
  FROM `${PROJECT_ID}.${DATASET}.signal_facts_reconciled_staging`
) AS 'fact staging must contain only the requested bounded range';

ASSERT (
  SELECT COUNTIF(NOT DATE(window_start) BETWEEN @start_date AND @end_date) = 0
  FROM `${PROJECT_ID}.${DATASET}.signal_features_reconciled_staging`
) AS 'feature staging must contain only the requested bounded range';

ASSERT (
  SELECT COUNT(*) = COUNT(DISTINCT event_id)
  FROM `${PROJECT_ID}.${DATASET}.signal_facts_reconciled_staging`
  WHERE DATE(occurred_at) BETWEEN @start_date AND @end_date
) AS 'fact staging must contain exactly one row per event_id';

ASSERT (
  SELECT COUNT(*) = COUNT(DISTINCT TO_JSON_STRING(STRUCT(
    window_name, window_start, kitchen_id, location_id, ingredient_id)))
  FROM `${PROJECT_ID}.${DATASET}.signal_features_reconciled_staging`
  WHERE DATE(window_start) BETWEEN @start_date AND @end_date
) AS 'feature staging must contain exactly one row per bounded feature key';

BEGIN TRANSACTION;

-- Full partition replacement removes existing duplicates and stale buckets.
DELETE FROM `${PROJECT_ID}.${DATASET}.signal_facts`
WHERE DATE(occurred_at) BETWEEN @start_date AND @end_date;

INSERT INTO `${PROJECT_ID}.${DATASET}.signal_facts`
SELECT *
FROM `${PROJECT_ID}.${DATASET}.signal_facts_reconciled_staging`
WHERE DATE(occurred_at) BETWEEN @start_date AND @end_date;

DELETE FROM `${PROJECT_ID}.${DATASET}.signal_features`
WHERE DATE(window_start) BETWEEN @start_date AND @end_date;

INSERT INTO `${PROJECT_ID}.${DATASET}.signal_features`
SELECT *
FROM `${PROJECT_ID}.${DATASET}.signal_features_reconciled_staging`
WHERE DATE(window_start) BETWEEN @start_date AND @end_date;

COMMIT TRANSACTION;

CREATE OR REPLACE VIEW `${PROJECT_ID}.${DATASET}.signal_features_current` AS
SELECT * EXCEPT (revision_rank)
FROM (
  SELECT *, ROW_NUMBER() OVER (
    PARTITION BY window_name, window_start, kitchen_id, location_id, ingredient_id
    ORDER BY pane_index DESC
  ) AS revision_rank
  FROM `${PROJECT_ID}.${DATASET}.signal_features`
)
WHERE revision_rank = 1;
