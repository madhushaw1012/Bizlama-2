CREATE TABLE recommendation_explanation_cache (
  input_hash VARCHAR(64) NOT NULL,
  prompt_version VARCHAR(80) NOT NULL,
  provider VARCHAR(80) NOT NULL,
  model VARCHAR(160) NOT NULL,
  recommendation_id VARCHAR(100) NOT NULL,
  recommendation_version INTEGER NOT NULL,
  calculation_id VARCHAR(100) NOT NULL,
  response_schema_version INTEGER NOT NULL,
  response_json TEXT NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (input_hash, prompt_version, provider, model),
  CONSTRAINT fk_explanation_cache_recommendation
    FOREIGN KEY (recommendation_id) REFERENCES recommendations(id),
  CONSTRAINT fk_explanation_cache_calculation
    FOREIGN KEY (calculation_id) REFERENCES demand_calculation_snapshots(id),
  CONSTRAINT chk_explanation_cache_hash
    CHECK (CHAR_LENGTH(input_hash) = 64),
  CONSTRAINT chk_explanation_cache_versions
    CHECK (recommendation_version > 0 AND response_schema_version > 0)
);

CREATE TABLE recommendation_explanation_audit (
  id VARCHAR(100) PRIMARY KEY,
  recommendation_id VARCHAR(100) NOT NULL,
  recommendation_version INTEGER NOT NULL,
  calculation_id VARCHAR(100) NOT NULL,
  provider VARCHAR(80) NOT NULL,
  model VARCHAR(160) NOT NULL,
  prompt_version VARCHAR(80) NOT NULL,
  input_hash VARCHAR(64) NOT NULL,
  response_schema_version INTEGER NOT NULL,
  latency_ms BIGINT NOT NULL,
  outcome VARCHAR(30) NOT NULL,
  error_code VARCHAR(160),
  error_message VARCHAR(600),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_explanation_audit_recommendation
    FOREIGN KEY (recommendation_id) REFERENCES recommendations(id),
  CONSTRAINT fk_explanation_audit_calculation
    FOREIGN KEY (calculation_id) REFERENCES demand_calculation_snapshots(id),
  CONSTRAINT chk_explanation_audit_hash
    CHECK (CHAR_LENGTH(input_hash) = 64),
  CONSTRAINT chk_explanation_audit_versions
    CHECK (recommendation_version > 0 AND response_schema_version > 0),
  CONSTRAINT chk_explanation_audit_latency
    CHECK (latency_ms >= 0),
  CONSTRAINT chk_explanation_audit_outcome
    CHECK (outcome IN ('SUCCESS', 'CACHE_HIT', 'FALLBACK', 'ERROR')),
  CONSTRAINT chk_explanation_audit_error
    CHECK (
      (outcome = 'ERROR' AND error_code IS NOT NULL)
      OR
      (outcome <> 'ERROR' AND error_code IS NULL AND error_message IS NULL)
    )
);

CREATE INDEX idx_explanation_cache_recommendation
  ON recommendation_explanation_cache(recommendation_id, recommendation_version);

CREATE INDEX idx_explanation_audit_recommendation
  ON recommendation_explanation_audit(recommendation_id, created_at DESC);

CREATE INDEX idx_explanation_audit_input
  ON recommendation_explanation_audit(input_hash, prompt_version, provider, model);
