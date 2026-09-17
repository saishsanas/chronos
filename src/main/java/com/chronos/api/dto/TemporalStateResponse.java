package com.chronos.api.dto;

import com.chronos.application.model.TemporalResult;
import java.time.Instant;
import java.util.UUID;

public record TemporalStateResponse(
    UUID aggregateId,
    Instant targetTimestamp,
    AccountStateResponse reconstructedState,
    long sequenceNumber,
    boolean snapshotUsed,
    long snapshotSequenceNumber,
    int eventsReplayedCount
) {
    public static TemporalStateResponse fromDomain(TemporalResult result) {
        if (result == null) return null;
        return new TemporalStateResponse(
            result.aggregateId(),
            result.targetTimestamp(),
            AccountStateResponse.fromDomain(result.reconstructedState()),
            result.sequenceNumber(),
            result.snapshotUsed(),
            result.snapshotSequenceNumber(),
            result.eventsReplayedCount()
        );
    }
}
