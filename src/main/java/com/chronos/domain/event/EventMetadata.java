package com.chronos.domain.event;

import java.util.UUID;

public record EventMetadata(
    UUID correlationId,
    UUID causationId,
    String actorId,
    String idempotencyKey
) {}
