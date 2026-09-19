-- Flyway Migration V9: Create Users and Roles Tables
CREATE TABLE application_users (
    user_id UUID PRIMARY KEY,
    username VARCHAR(64) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_users_username ON application_users (username);

CREATE TABLE user_roles (
    user_id UUID NOT NULL REFERENCES application_users (user_id) ON DELETE CASCADE,
    role VARCHAR(32) NOT NULL,
    PRIMARY KEY (user_id, role)
);

CREATE INDEX idx_user_roles_role ON user_roles (role);
