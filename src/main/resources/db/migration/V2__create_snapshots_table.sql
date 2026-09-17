-- Flyway Migration V2: Create Snapshots Table
CREATE TABLE snapshots (
    snapshot_id UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL,
    sequence_number BIGINT NOT NULL,
    snapshot_version INT NOT NULL DEFAULT 1,
    domain_version INT NOT NULL DEFAULT 1,
    replay_logic_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    state_payload JSONB NOT NULL,
    CONSTRAINT uk_snapshot_aggregate_sequence_version UNIQUE (aggregate_id, sequence_number, snapshot_version)
);

CREATE INDEX idx_snapshots_aggregate_sequence ON snapshots (aggregate_id, sequence_number DESC);
