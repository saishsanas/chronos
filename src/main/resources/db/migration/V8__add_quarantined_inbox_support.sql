-- Flyway Migration V8: Add Quarantined Inbox Index & Support
CREATE INDEX idx_inbox_quarantined ON inbox_events (status) WHERE status = 'QUARANTINED';
