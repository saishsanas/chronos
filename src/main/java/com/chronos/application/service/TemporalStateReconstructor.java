package com.chronos.application.service;

import com.chronos.application.model.TemporalResult;
import com.chronos.application.port.EventStore;
import com.chronos.application.port.SnapshotRepository;
import com.chronos.domain.account.AccountReducer;
import com.chronos.domain.account.AccountState;
import com.chronos.domain.event.DomainEventEnvelope;
import com.chronos.domain.snapshot.Snapshot;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class TemporalStateReconstructor {

    private final EventStore eventStore;
    private final SnapshotRepository snapshotRepository;

    public TemporalStateReconstructor(EventStore eventStore, SnapshotRepository snapshotRepository) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore must not be null");
        this.snapshotRepository = Objects.requireNonNull(snapshotRepository, "snapshotRepository must not be null");
    }

    public TemporalResult reconstructCurrentState(UUID aggregateId) {
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");

        Optional<Snapshot> latestSnapshotOpt = snapshotRepository.findLatestForAggregate(aggregateId);

        if (latestSnapshotOpt.isPresent() && latestSnapshotOpt.get().isValid()) {
            Snapshot snapshot = latestSnapshotOpt.get();
            long snapshotSeq = snapshot.sequenceNumber();
            List<DomainEventEnvelope> events = eventStore.loadStreamFrom(aggregateId, snapshotSeq);

            AccountState state = snapshot.state();
            for (DomainEventEnvelope event : events) {
                state = AccountReducer.reduce(state, event);
            }

            return new TemporalResult(aggregateId, state, null, state.sequenceNumber(), true, snapshotSeq, events.size());
        }

        // Fallback: Full event replay
        return reconstructFullReplayCurrentState(aggregateId);
    }

    public TemporalResult reconstructStateAt(UUID aggregateId, Instant targetTimestamp) {
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        Objects.requireNonNull(targetTimestamp, "targetTimestamp must not be null");

        Optional<Snapshot> snapshotOpt = snapshotRepository.findLatestAtOrBeforeTimestamp(aggregateId, targetTimestamp);

        if (snapshotOpt.isPresent() && snapshotOpt.get().isValid()) {
            Snapshot snapshot = snapshotOpt.get();
            // Ensure snapshot state timestamp does not exceed target timestamp
            if (!snapshot.state().lastUpdatedAt().isAfter(targetTimestamp)) {
                long snapshotSeq = snapshot.sequenceNumber();
                List<DomainEventEnvelope> events = eventStore.loadStreamFromAndUpTo(aggregateId, snapshotSeq, targetTimestamp);

                AccountState state = snapshot.state();
                for (DomainEventEnvelope event : events) {
                    state = AccountReducer.reduce(state, event);
                }

                return new TemporalResult(aggregateId, state, targetTimestamp, state.sequenceNumber(), true, snapshotSeq, events.size());
            }
        }

        // Fallback: Full event replay up to targetTimestamp
        return reconstructFullReplayStateAt(aggregateId, targetTimestamp);
    }

    public TemporalResult reconstructFullReplayCurrentState(UUID aggregateId) {
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");

        List<DomainEventEnvelope> events = eventStore.loadStream(aggregateId);
        AccountState state = AccountState.uninitialized(aggregateId);
        for (DomainEventEnvelope event : events) {
            state = AccountReducer.reduce(state, event);
        }

        return new TemporalResult(aggregateId, state, null, state.sequenceNumber(), false, 0L, events.size());
    }

    public TemporalResult reconstructFullReplayStateAt(UUID aggregateId, Instant targetTimestamp) {
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        Objects.requireNonNull(targetTimestamp, "targetTimestamp must not be null");

        List<DomainEventEnvelope> events = eventStore.loadStreamUpTo(aggregateId, targetTimestamp);
        AccountState state = AccountState.uninitialized(aggregateId);
        for (DomainEventEnvelope event : events) {
            state = AccountReducer.reduce(state, event);
        }

        return new TemporalResult(aggregateId, state, targetTimestamp, state.sequenceNumber(), false, 0L, events.size());
    }
}
