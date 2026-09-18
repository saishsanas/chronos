package com.chronos.application.port;

import com.chronos.domain.projection.ProjectionRebuildJob;

import java.util.Optional;
import java.util.UUID;

public interface ProjectionRebuildJobRepository {
    void save(ProjectionRebuildJob job);
    Optional<ProjectionRebuildJob> findById(UUID jobId);
    Optional<ProjectionRebuildJob> findLatestForProjection(String projectionName);
    void markStarted(UUID jobId);
    void markSucceeded(UUID jobId, long eventsProcessed, long resultingSequence);
    void markFailed(UUID jobId, String errorDetails);
}
