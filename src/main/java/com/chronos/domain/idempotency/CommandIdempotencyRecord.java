package com.chronos.domain.idempotency;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record CommandIdempotencyRecord(
    UUID idempotencyId,
    String actorId,
    String idempotencyKey,
    String requestHash,
    String commandType,
    UUID aggregateId,
    IdempotencyStatus status,
    Integer responseStatus,
    String responsePayload,
    Instant createdAt,
    Instant completedAt,
    Instant expiresAt
) {
    public CommandIdempotencyRecord {
        Objects.requireNonNull(idempotencyId, "idempotencyId must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(requestHash, "requestHash must not be null");
        Objects.requireNonNull(commandType, "commandType must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public static CommandIdempotencyRecord createInFlight(
        String actorId,
        String idempotencyKey,
        String requestHash,
        String commandType,
        UUID aggregateId
    ) {
        return new CommandIdempotencyRecord(
            UUID.randomUUID(),
            actorId,
            idempotencyKey,
            requestHash,
            commandType,
            aggregateId,
            IdempotencyStatus.IN_FLIGHT,
            null,
            null,
            Instant.now(),
            null,
            null
        );
    }
}
