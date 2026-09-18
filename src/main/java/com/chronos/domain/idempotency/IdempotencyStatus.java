package com.chronos.domain.idempotency;

public enum IdempotencyStatus {
    IN_FLIGHT,
    COMPLETED,
    FAILED
}
