-- Flyway Migration V1: Create Event Store Table
CREATE TABLE event_store (
    event_id UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    sequence_number BIGINT NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    event_version INT NOT NULL DEFAULT 1,
    recorded_at TIMESTAMPTZ NOT NULL,
    metadata JSONB NOT NULL,
    payload JSONB NOT NULL,
    CONSTRAINT uk_aggregate_sequence UNIQUE (aggregate_id, sequence_number)
);

CREATE INDEX idx_event_store_lookup ON event_store (aggregate_id, sequence_number ASC);
CREATE INDEX idx_event_store_recorded ON event_store (aggregate_id, recorded_at ASC);
