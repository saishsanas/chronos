package com.chronos.application.command;

import com.chronos.domain.account.AccountState;
import com.chronos.domain.event.DomainEventEnvelope;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record CommandResult(
    UUID aggregateId,
    AccountState resultingState,
    List<DomainEventEnvelope> emittedEvents,
    long finalSequenceNumber
) {
    public CommandResult {
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        Objects.requireNonNull(resultingState, "resultingState must not be null");
        Objects.requireNonNull(emittedEvents, "emittedEvents must not be null");
    }
}
