package com.chronos.application.command;

import com.chronos.domain.event.EventMetadata;
import java.util.Objects;
import java.util.UUID;

public record CommandContext(
    UUID correlationId,
    UUID causationId,
    String actorId,
    String idempotencyKey
) {
    public CommandContext {
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        Objects.requireNonNull(causationId, "causationId must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
    }

    public static CommandContext of(String actorId) {
        UUID id = UUID.randomUUID();
        return new CommandContext(id, id, actorId, null);
    }

    public static CommandContext of(String actorId, String idempotencyKey) {
        UUID id = UUID.randomUUID();
        return new CommandContext(id, id, actorId, idempotencyKey);
    }

    public static CommandContext of(UUID correlationId, UUID causationId, String actorId, String idempotencyKey) {
        return new CommandContext(correlationId, causationId, actorId, idempotencyKey);
    }

    public EventMetadata toMetadata() {
        return new EventMetadata(correlationId, causationId, actorId, idempotencyKey);
    }
}
