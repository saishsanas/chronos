-- Flyway Migration V6: Create Command Idempotency Table
CREATE TABLE command_idempotency (
    idempotency_id UUID PRIMARY KEY,
    actor_id VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    command_type VARCHAR(128) NOT NULL,
    aggregate_id UUID NULL,
    status VARCHAR(20) NOT NULL,
    response_status INT NULL,
    response_payload JSONB NULL,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NULL,
    expires_at TIMESTAMPTZ NULL,
    CONSTRAINT uk_actor_idempotency UNIQUE (actor_id, idempotency_key)
);

CREATE INDEX idx_idempotency_actor_key ON command_idempotency (actor_id, idempotency_key);
CREATE INDEX idx_idempotency_created ON command_idempotency (created_at ASC);
