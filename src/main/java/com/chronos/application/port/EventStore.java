package com.chronos.application.port;

import com.chronos.domain.event.DomainEventEnvelope;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface EventStore {

    void append(UUID aggregateId, long expectedVersion, List<DomainEventEnvelope> events);

    List<DomainEventEnvelope> loadStream(UUID aggregateId);

    List<DomainEventEnvelope> loadStreamUpTo(UUID aggregateId, Instant timestamp);

    List<DomainEventEnvelope> loadStreamFrom(UUID aggregateId, long fromSequenceNumber);

    List<DomainEventEnvelope> loadStreamFromAndUpTo(UUID aggregateId, long fromSequenceNumber, Instant timestamp);

    long currentVersion(UUID aggregateId);
}

