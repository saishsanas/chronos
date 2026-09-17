-- Flyway Migration V4: Create Inbox Events and Consumer Aggregate State Tables
CREATE TABLE inbox_events (
    inbox_id UUID PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    aggregate_id UUID NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    sequence_number BIGINT NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    event_version INT NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ NULL,
    status VARCHAR(20) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    last_error TEXT NULL
);

CREATE INDEX idx_inbox_event_id ON inbox_events (event_id);
CREATE INDEX idx_inbox_aggregate_seq ON inbox_events (aggregate_id, sequence_number ASC);
CREATE INDEX idx_inbox_status ON inbox_events (status);

CREATE TABLE consumer_aggregate_state (
    aggregate_id UUID PRIMARY KEY,
    last_sequence_number BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
