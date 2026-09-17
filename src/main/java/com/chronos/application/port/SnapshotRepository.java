package com.chronos.application.port;

import com.chronos.domain.snapshot.Snapshot;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface SnapshotRepository {

    void save(Snapshot snapshot);

    Optional<Snapshot> findLatestForAggregate(UUID aggregateId);

    Optional<Snapshot> findLatestAtOrBeforeSequence(UUID aggregateId, long sequenceNumber);

    Optional<Snapshot> findLatestAtOrBeforeTimestamp(UUID aggregateId, Instant timestamp);
}
