package com.chronos.domain.outbox;

public enum OutboxStatus {
    PENDING,
    IN_FLIGHT,
    PUBLISHED
}
