package com.chronos.api.dto;

import com.chronos.domain.event.DomainEventEnvelope;
import com.chronos.domain.event.EventMetadata;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record EventEnvelopeResponse(
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
    public static EventEnvelopeResponse fromDomain(DomainEventEnvelope env) {
        if (env == null) return null;
        return new EventEnvelopeResponse(
            env.eventId(),
            env.aggregateId(),
            env.aggregateType(),
            env.sequenceNumber(),
            env.eventType(),
            env.eventVersion(),
            env.recordedAt(),
            env.metadata(),
            env.payload()
        );
    }
}
