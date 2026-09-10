-- Transactional outbox envelope and delivery state.
--
-- The original V4 columns remain in place because older application code writes
-- them directly. New envelope identifiers are nullable for that same reason;
-- the dispatcher falls back to the legacy id/aggregate columns for such rows.

ALTER TABLE analytics_outbox ADD COLUMN event_id VARCHAR(100);
ALTER TABLE analytics_outbox ADD COLUMN schema_version INTEGER NOT NULL DEFAULT 1;
ALTER TABLE analytics_outbox ADD COLUMN entity_type VARCHAR(80);
ALTER TABLE analytics_outbox ADD COLUMN entity_id VARCHAR(120);
ALTER TABLE analytics_outbox ADD COLUMN recorded_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE analytics_outbox ADD COLUMN correlation_id VARCHAR(120);
ALTER TABLE analytics_outbox ADD COLUMN causation_id VARCHAR(120);
ALTER TABLE analytics_outbox ADD COLUMN deduplication_key VARCHAR(200);
ALTER TABLE analytics_outbox ADD COLUMN source_metadata_json TEXT NOT NULL DEFAULT '{}';
ALTER TABLE analytics_outbox ADD COLUMN state VARCHAR(24) NOT NULL DEFAULT 'PENDING';
ALTER TABLE analytics_outbox ADD COLUMN next_attempt_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE analytics_outbox ADD COLUMN lease_owner VARCHAR(150);
ALTER TABLE analytics_outbox ADD COLUMN lease_expires_at TIMESTAMP WITH TIME ZONE;

UPDATE analytics_outbox
SET event_id = id,
    entity_type = aggregate_type,
    entity_id = aggregate_id,
    recorded_at = occurred_at,
    deduplication_key = id,
    state = CASE
        WHEN published_at IS NULL THEN 'PENDING'
        ELSE 'PUBLISHED'
    END,
    next_attempt_at = CASE
        WHEN published_at IS NULL THEN occurred_at
        ELSE NULL
    END;

ALTER TABLE analytics_outbox
    ADD CONSTRAINT ck_analytics_outbox_schema_version_positive
    CHECK (schema_version >= 1);

ALTER TABLE analytics_outbox
    ADD CONSTRAINT ck_analytics_outbox_state
    CHECK (state IN ('PENDING', 'IN_FLIGHT', 'PUBLISHED', 'DEAD_LETTER'));

ALTER TABLE analytics_outbox
    ADD CONSTRAINT ck_analytics_outbox_pending_has_next_attempt
    CHECK (state <> 'PENDING' OR next_attempt_at IS NOT NULL);

CREATE UNIQUE INDEX uq_analytics_outbox_event_id
    ON analytics_outbox(event_id);

CREATE UNIQUE INDEX uq_analytics_outbox_deduplication
    ON analytics_outbox(kitchen_id, deduplication_key);

CREATE INDEX idx_analytics_outbox_dispatch
    ON analytics_outbox(state, next_attempt_at, lease_expires_at, occurred_at);

