package com.chronos.application.model;

import com.chronos.domain.account.AccountState;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record TemporalResult(
    UUID aggregateId,
    AccountState reconstructedState,
    Instant targetTimestamp,
    long sequenceNumber,
    boolean snapshotUsed,
    long snapshotSequenceNumber,
    int eventsReplayedCount
) {
    public TemporalResult {
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        Objects.requireNonNull(reconstructedState, "reconstructedState must not be null");
        if (sequenceNumber < 0) {
            throw new IllegalArgumentException("sequenceNumber must be >= 0");
        }
        if (eventsReplayedCount < 0) {
            throw new IllegalArgumentException("eventsReplayedCount must be >= 0");
        }
    }
}
