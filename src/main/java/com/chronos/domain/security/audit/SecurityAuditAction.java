package com.chronos.domain.security.audit;

public enum SecurityAuditAction {
    LOGIN_SUCCESS,
    LOGIN_FAILURE,
    ACCOUNT_COMMAND,
    PROJECTION_REBUILD,
    SECURITY_DENIED,
    SECURITY_GRANTED
}
