-- Flyway Migration V7: Create Projection Rebuild Jobs Table
CREATE TABLE projection_rebuild_jobs (
    job_id UUID PRIMARY KEY,
    projection_name VARCHAR(64) NOT NULL,
    scope VARCHAR(32) NOT NULL,
    target_account_id UUID NULL,
    status VARCHAR(20) NOT NULL,
    rebuild_generation BIGINT NOT NULL DEFAULT 1,
    requested_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ NULL,
    completed_at TIMESTAMPTZ NULL,
    events_processed BIGINT NOT NULL DEFAULT 0,
    resulting_sequence BIGINT NOT NULL DEFAULT 0,
    error_details TEXT NULL
);

CREATE INDEX idx_rebuild_jobs_status ON projection_rebuild_jobs (status);
CREATE INDEX idx_rebuild_jobs_requested ON projection_rebuild_jobs (requested_at DESC);
