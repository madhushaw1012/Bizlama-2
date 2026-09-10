-- Durable receipt-processing attempts and recovery of interrupted legacy work.

CREATE TABLE receipt_processing_attempts (
  id VARCHAR(100) PRIMARY KEY,
  receipt_id VARCHAR(100) NOT NULL,
  kitchen_id VARCHAR(80) NOT NULL,
  location_id VARCHAR(100) NOT NULL,
  attempt_number INTEGER NOT NULL,
  attempt_trigger VARCHAR(24) NOT NULL,
  outcome VARCHAR(24) NOT NULL,
  extraction_provider VARCHAR(80) NOT NULL,
  extraction_model VARCHAR(160) NOT NULL,
  extraction_schema_version VARCHAR(40) NOT NULL,
  failure_stage VARCHAR(24),
  failure_code VARCHAR(80),
  failure_detail VARCHAR(1000),
  started_at TIMESTAMP WITH TIME ZONE NOT NULL,
  completed_at TIMESTAMP WITH TIME ZONE,
  CONSTRAINT fk_receipt_processing_attempt_scope
    FOREIGN KEY (receipt_id, kitchen_id, location_id)
    REFERENCES receipt_imports(id, kitchen_id, location_id)
    ON DELETE CASCADE,
  CONSTRAINT uq_receipt_processing_attempt_number
    UNIQUE (receipt_id, attempt_number),
  CONSTRAINT ck_receipt_processing_attempt_positive
    CHECK (attempt_number > 0),
  CONSTRAINT ck_receipt_processing_attempt_trigger
    CHECK (attempt_trigger IN ('UPLOAD', 'RETRY', 'LEGACY_RECOVERY')),
  CONSTRAINT ck_receipt_processing_attempt_outcome
    CHECK (outcome IN ('IN_PROGRESS', 'SUCCEEDED', 'MANUAL_REVIEW')),
  CONSTRAINT ck_receipt_processing_attempt_failure_stage
    CHECK (
      failure_stage IS NULL
      OR failure_stage IN ('EXTRACTION', 'NORMALIZATION', 'PERSISTENCE')
    ),
  CONSTRAINT ck_receipt_processing_attempt_completion
    CHECK (
      (outcome = 'IN_PROGRESS'
       AND completed_at IS NULL
       AND failure_stage IS NULL
       AND failure_code IS NULL
       AND failure_detail IS NULL)
      OR
      (outcome = 'SUCCEEDED'
       AND completed_at IS NOT NULL
       AND failure_stage IS NULL
       AND failure_code IS NULL
       AND failure_detail IS NULL)
      OR
      (outcome = 'MANUAL_REVIEW'
       AND completed_at IS NOT NULL
       AND failure_stage IS NOT NULL
       AND failure_code IS NOT NULL
       AND failure_detail IS NOT NULL)
    )
);

CREATE INDEX idx_receipt_processing_attempt_scope
  ON receipt_processing_attempts(
    kitchen_id,
    location_id,
    receipt_id,
    attempt_number DESC
  );

-- Previous synchronous processing could be interrupted in UPLOADED or
-- EXTRACTING, while FAILED receipts had no manual path. Preserve those rows,
-- create an audit entry, and make their retained evidence manually reviewable.
INSERT INTO receipt_processing_attempts
(id, receipt_id, kitchen_id, location_id, attempt_number, attempt_trigger,
 outcome, extraction_provider, extraction_model,
 extraction_schema_version, failure_stage, failure_code, failure_detail,
 started_at, completed_at)
SELECT
 receipt.id,
 receipt.id,
 receipt.kitchen_id,
 receipt.location_id,
 1,
 'LEGACY_RECOVERY',
 'MANUAL_REVIEW',
 COALESCE(receipt.extraction_provider, 'legacy-unknown'),
 COALESCE(receipt.extraction_model, 'legacy-unknown'),
 COALESCE(receipt.extraction_schema_version, 'legacy-unknown'),
 CASE
   WHEN receipt.status = 'FAILED' THEN 'EXTRACTION'
   ELSE 'PERSISTENCE'
 END,
 CASE
   WHEN receipt.status = 'FAILED'
     THEN COALESCE(receipt.failure_code, 'LEGACY_PROCESSING_FAILED')
   ELSE 'LEGACY_PROCESSING_INTERRUPTED'
 END,
 CASE
   WHEN receipt.status = 'FAILED'
     THEN COALESCE(
       receipt.failure_message,
       'Legacy receipt processing failed; evidence retained for manual review.'
     )
   ELSE
     'Legacy receipt processing was interrupted; evidence retained for manual review.'
 END,
 receipt.created_at,
 receipt.updated_at
FROM receipt_imports receipt
WHERE receipt.status IN ('UPLOADED', 'EXTRACTING', 'FAILED');

UPDATE receipt_imports
SET status = 'REVIEW_REQUIRED',
    failure_code = CASE
      WHEN status = 'FAILED'
        THEN COALESCE(failure_code, 'LEGACY_PROCESSING_FAILED')
      ELSE 'LEGACY_PROCESSING_INTERRUPTED'
    END,
    failure_message = CASE
      WHEN status = 'FAILED'
        THEN COALESCE(
          failure_message,
          'Legacy receipt processing failed; evidence retained for manual review.'
        )
      ELSE
        'Legacy receipt processing was interrupted; evidence retained for manual review.'
    END,
    updated_at = CURRENT_TIMESTAMP,
    version = version + 1
WHERE status IN ('UPLOADED', 'EXTRACTING', 'FAILED');
