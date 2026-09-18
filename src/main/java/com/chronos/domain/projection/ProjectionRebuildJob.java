package com.chronos.domain.projection;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ProjectionRebuildJob(
    UUID jobId,
    String projectionName,
    RebuildScope scope,
    UUID targetAccountId,
    RebuildStatus status,
    long rebuildGeneration,
    Instant requestedAt,
    Instant startedAt,
    Instant completedAt,
    long eventsProcessed,
    long resultingSequence,
    String errorDetails
) {
    public ProjectionRebuildJob {
        Objects.requireNonNull(jobId, "jobId must not be null");
        Objects.requireNonNull(projectionName, "projectionName must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(requestedAt, "requestedAt must not be null");
    }

    public static ProjectionRebuildJob createFull(String projectionName) {
        return new ProjectionRebuildJob(
            UUID.randomUUID(),
            projectionName,
            RebuildScope.FULL,
            null,
            RebuildStatus.REQUESTED,
            1L,
            Instant.now(),
            null,
            null,
            0L,
            0L,
            null
        );
    }

    public static ProjectionRebuildJob createTargeted(String projectionName, UUID targetAccountId) {
        Objects.requireNonNull(targetAccountId, "targetAccountId must not be null");
        return new ProjectionRebuildJob(
            UUID.randomUUID(),
            projectionName,
            RebuildScope.TARGETED,
            targetAccountId,
            RebuildStatus.REQUESTED,
            1L,
            Instant.now(),
            null,
            null,
            0L,
            0L,
            null
        );
    }
}
