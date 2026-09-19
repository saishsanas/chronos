-- Flyway Migration V10: Create Security Audit Log Table
CREATE TABLE security_audit_log (
    audit_id UUID PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL,
    actor_user_id UUID NULL,
    actor_username VARCHAR(64) NULL,
    action VARCHAR(64) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    resource_id VARCHAR(128) NULL,
    outcome VARCHAR(32) NOT NULL,
    correlation_id VARCHAR(128) NULL,
    details JSONB NULL
);

CREATE INDEX idx_security_audit_occurred ON security_audit_log (occurred_at DESC);
CREATE INDEX idx_security_audit_actor ON security_audit_log (actor_username);
CREATE INDEX idx_security_audit_action ON security_audit_log (action);
