package com.chronos.domain.outbox;

import com.chronos.domain.event.DomainEventEnvelope;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record OutboxEventRecord(
    UUID outboxId,
    UUID eventId,
    UUID aggregateId,
    String aggregateType,
    long sequenceNumber,
    String eventType,
    int eventVersion,
    Instant recordedAt,
    DomainEventEnvelope envelope,
    OutboxStatus status,
    int attempts,
    Instant nextAttemptAt,
    Instant lockedUntil,
    String lockedBy,
    Instant publishedAt,
    String lastError,
    Instant createdAt
) {
    public OutboxEventRecord {
        Objects.requireNonNull(outboxId, "outboxId must not be null");
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        Objects.requireNonNull(aggregateType, "aggregateType must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(recordedAt, "recordedAt must not be null");
        Objects.requireNonNull(envelope, "envelope must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public static OutboxEventRecord fromEnvelope(DomainEventEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope must not be null");
        Instant now = Instant.now();
        return new OutboxEventRecord(
            UUID.randomUUID(),
            envelope.eventId(),
            envelope.aggregateId(),
            envelope.aggregateType(),
            envelope.sequenceNumber(),
            envelope.eventType(),
            envelope.eventVersion(),
            envelope.recordedAt(),
            envelope,
            OutboxStatus.PENDING,
            0,
            now,
            null,
            null,
            null,
            null,
            now
        );
    }
}
