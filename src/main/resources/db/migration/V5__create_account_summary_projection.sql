-- Flyway Migration V5: Create CQRS Account Summary Projection Table
CREATE TABLE account_summary_projection (
    account_id UUID PRIMARY KEY,
    currency VARCHAR(3) NOT NULL,
    balance_minor BIGINT NOT NULL,
    overdraft_limit_minor BIGINT NOT NULL,
    transaction_limit_minor BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    sequence_number BIGINT NOT NULL,
    last_updated_at TIMESTAMPTZ NOT NULL,
    projected_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_projection_status ON account_summary_projection (status);
