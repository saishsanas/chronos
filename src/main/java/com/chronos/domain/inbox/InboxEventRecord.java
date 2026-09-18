package com.chronos.domain.inbox;

import com.chronos.domain.event.DomainEventEnvelope;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record InboxEventRecord(
    UUID inboxId,
    UUID eventId,
    UUID aggregateId,
    String aggregateType,
    long sequenceNumber,
    String eventType,
    int eventVersion,
    Instant receivedAt,
    Instant processedAt,
    InboxStatus status,
    int attempts,
    String lastError
) {
    public InboxEventRecord {
        Objects.requireNonNull(inboxId, "inboxId must not be null");
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        Objects.requireNonNull(aggregateType, "aggregateType must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(receivedAt, "receivedAt must not be null");
        Objects.requireNonNull(status, "status must not be null");
    }

    public static InboxEventRecord fromEnvelope(DomainEventEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope must not be null");
        Instant now = Instant.now();
        return new InboxEventRecord(
            UUID.randomUUID(),
            envelope.eventId(),
            envelope.aggregateId(),
            envelope.aggregateType(),
            envelope.sequenceNumber(),
            envelope.eventType(),
            envelope.eventVersion(),
            now,
            null,
            InboxStatus.RECEIVED,
            0,
            null
        );
    }

    public static InboxEventRecord createQuarantined(DomainEventEnvelope envelope, String errorDetails) {
        Objects.requireNonNull(envelope, "envelope must not be null");
        Instant now = Instant.now();
        return new InboxEventRecord(
            UUID.randomUUID(),
            envelope.eventId(),
            envelope.aggregateId() != null ? envelope.aggregateId() : UUID.randomUUID(),
            envelope.aggregateType() != null ? envelope.aggregateType() : "UNKNOWN",
            envelope.sequenceNumber(),
            envelope.eventType() != null ? envelope.eventType() : "UNKNOWN",
            envelope.eventVersion(),
            now,
            null,
            InboxStatus.QUARANTINED,
            1,
            errorDetails
        );
    }
}
