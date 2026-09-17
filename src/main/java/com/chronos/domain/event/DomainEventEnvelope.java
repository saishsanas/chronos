package com.chronos.domain.event;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record DomainEventEnvelope(
    UUID eventId,
    UUID aggregateId,
    String aggregateType,
    long sequenceNumber,
    String eventType,
    int eventVersion,
    Instant recordedAt,
    EventMetadata metadata,
    JsonNode payload
) {
    public DomainEventEnvelope {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        Objects.requireNonNull(aggregateType, "aggregateType must not be null");
        if (sequenceNumber < 1) {
            throw new IllegalArgumentException("sequenceNumber must be >= 1");
        }
        Objects.requireNonNull(eventType, "eventType must not be null");
        if (eventVersion < 1) {
            throw new IllegalArgumentException("eventVersion must be >= 1");
        }
        Objects.requireNonNull(recordedAt, "recordedAt must not be null");
        Objects.requireNonNull(metadata, "metadata must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
    }
}
