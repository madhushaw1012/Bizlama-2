-- Durable, non-executable inbox for proposal commands emitted by the signal
-- engine. Suggested quantities are evidence only: these rows deliberately have
-- no foreign key or state transition into the executable recommendations table.

CREATE TABLE governed_signal_proposals (
  proposal_id VARCHAR(100) PRIMARY KEY,
  schema_version INTEGER NOT NULL,
  command_type VARCHAR(80) NOT NULL,
  signal_type VARCHAR(40) NOT NULL,
  risk_tier VARCHAR(16) NOT NULL,
  kitchen_id VARCHAR(80) NOT NULL,
  location_id VARCHAR(100) NOT NULL,
  ingredient_id VARCHAR(80) NOT NULL,
  window_name VARCHAR(16) NOT NULL,
  window_start TIMESTAMP WITH TIME ZONE NOT NULL,
  window_end TIMESTAMP WITH TIME ZONE NOT NULL,
  suggested_quantity DECIMAL(30,12),
  canonical_unit VARCHAR(24),
  reason_code VARCHAR(100) NOT NULL,
  evidence_json TEXT NOT NULL,
  target_queue VARCHAR(400) NOT NULL,
  direct_mutation_allowed BOOLEAN NOT NULL DEFAULT FALSE,
  command_sha256 VARCHAR(64) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  version INTEGER NOT NULL DEFAULT 1,
  received_at TIMESTAMP WITH TIME ZONE NOT NULL,
  first_message_id VARCHAR(200) NOT NULL,
  first_publish_time TIMESTAMP WITH TIME ZONE NOT NULL,
  dismissed_at TIMESTAMP WITH TIME ZONE,
  dismissed_by VARCHAR(200),
  dismissal_reason VARCHAR(500),
  CONSTRAINT fk_governed_signal_proposals_kitchen
    FOREIGN KEY (kitchen_id) REFERENCES kitchens(id),
  CONSTRAINT fk_governed_signal_proposals_location_scope
    FOREIGN KEY (kitchen_id, location_id)
    REFERENCES kitchen_locations(kitchen_id, id),
  CONSTRAINT fk_governed_signal_proposals_ingredient_scope
    FOREIGN KEY (kitchen_id, ingredient_id)
    REFERENCES ingredients(kitchen_id, id),
  CONSTRAINT ck_governed_signal_proposals_schema
    CHECK (schema_version = 1),
  CONSTRAINT ck_governed_signal_proposals_command
    CHECK (command_type = 'CREATE_GOVERNED_RECOMMENDATION_PROPOSAL'),
  CONSTRAINT ck_governed_signal_proposals_signal_type
    CHECK (signal_type IN (
      'SHORTAGE_RISK',
      'EXPIRY_RISK_SURPLUS',
      'MATERIAL_DATA_QUALITY'
    )),
  CONSTRAINT ck_governed_signal_proposals_risk
    CHECK (risk_tier = 'HIGH'),
  CONSTRAINT ck_governed_signal_proposals_window
    CHECK (window_name IN ('15m', '1h', 'daily') AND window_end > window_start),
  CONSTRAINT ck_governed_signal_proposals_non_executable
    CHECK (direct_mutation_allowed = FALSE),
  CONSTRAINT ck_governed_signal_proposals_quantity
    CHECK (
      (signal_type IN ('SHORTAGE_RISK', 'EXPIRY_RISK_SURPLUS')
        AND suggested_quantity > 0
        AND canonical_unit IS NOT NULL)
      OR
      (signal_type = 'MATERIAL_DATA_QUALITY'
        AND suggested_quantity IS NULL)
    ),
  CONSTRAINT ck_governed_signal_proposals_hash
    CHECK (CHAR_LENGTH(command_sha256) = 64),
  CONSTRAINT ck_governed_signal_proposals_id
    CHECK (
      CHAR_LENGTH(proposal_id) = 28
      AND SUBSTRING(proposal_id, 1, 4) = 'SIG-'
    ),
  CONSTRAINT ck_governed_signal_proposals_status
    CHECK (status IN ('PENDING', 'DISMISSED')),
  CONSTRAINT ck_governed_signal_proposals_version
    CHECK (version > 0),
  CONSTRAINT ck_governed_signal_proposals_dismissal
    CHECK (
      (status = 'PENDING'
        AND dismissed_at IS NULL
        AND dismissed_by IS NULL
        AND dismissal_reason IS NULL)
      OR
      (status = 'DISMISSED'
        AND dismissed_at IS NOT NULL
        AND dismissed_by IS NOT NULL
        AND dismissal_reason IS NOT NULL)
    ),
  CONSTRAINT uq_governed_signal_proposals_hash
    UNIQUE (command_sha256)
);

CREATE INDEX idx_governed_signal_proposals_operator_queue
  ON governed_signal_proposals(
    kitchen_id,
    location_id,
    status,
    received_at DESC
  );

-- Every delivery, including malformed input, is retained with an exact body
-- hash and Base64 body. Rejected input is therefore observable without making
-- it eligible for an operator action.
CREATE TABLE signal_proposal_deliveries (
  delivery_id VARCHAR(100) PRIMARY KEY,
  source_subscription VARCHAR(300) NOT NULL,
  pubsub_message_id VARCHAR(200) NOT NULL,
  pubsub_publish_time TIMESTAMP WITH TIME ZONE,
  claimed_proposal_id VARCHAR(100),
  payload_sha256 VARCHAR(64) NOT NULL,
  payload_base64 TEXT NOT NULL,
  attributes_json TEXT NOT NULL,
  delivery_attempt INTEGER,
  outcome VARCHAR(24) NOT NULL,
  rejection_code VARCHAR(80),
  rejection_detail VARCHAR(1000),
  received_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT uq_signal_proposal_deliveries_message_body
    UNIQUE (source_subscription, pubsub_message_id, payload_sha256),
  CONSTRAINT ck_signal_proposal_deliveries_hash
    CHECK (CHAR_LENGTH(payload_sha256) = 64),
  CONSTRAINT ck_signal_proposal_deliveries_attempt
    CHECK (delivery_attempt IS NULL OR delivery_attempt > 0),
  CONSTRAINT ck_signal_proposal_deliveries_outcome
    CHECK (outcome IN ('ACCEPTED', 'DUPLICATE', 'REJECTED')),
  CONSTRAINT ck_signal_proposal_deliveries_rejection
    CHECK (
      (outcome IN ('ACCEPTED', 'DUPLICATE')
        AND claimed_proposal_id IS NOT NULL
        AND rejection_code IS NULL
        AND rejection_detail IS NULL)
      OR
      (outcome = 'REJECTED'
        AND rejection_code IS NOT NULL
        AND rejection_detail IS NOT NULL)
    )
);

CREATE INDEX idx_signal_proposal_deliveries_outcome_time
  ON signal_proposal_deliveries(outcome, received_at DESC);

