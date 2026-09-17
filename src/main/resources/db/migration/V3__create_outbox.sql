-- Flyway Migration V3: Create Transactional Outbox Table
CREATE TABLE outbox_events (
    outbox_id UUID PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    aggregate_id UUID NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    sequence_number BIGINT NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    event_version INT NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    event_envelope JSONB NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    locked_until TIMESTAMPTZ NULL,
    locked_by VARCHAR(128) NULL,
    published_at TIMESTAMPTZ NULL,
    last_error TEXT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_outbox_status_next_attempt ON outbox_events (status, next_attempt_at);
CREATE INDEX idx_outbox_status_locked_until ON outbox_events (status, locked_until);
CREATE INDEX idx_outbox_aggregate_seq ON outbox_events (aggregate_id, sequence_number ASC);
