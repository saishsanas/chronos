package com.chronos.api.dto;

import com.chronos.application.command.CommandResult;
import java.util.UUID;

public record CommandExecutionResponse(
    UUID aggregateId,
    long sequenceNumber,
    AccountStateResponse newState,
    UUID correlationId,
    int emittedEventCount
) {
    public static CommandExecutionResponse fromDomain(CommandResult result, UUID correlationId) {
        if (result == null) return null;
        return new CommandExecutionResponse(
            result.aggregateId(),
            result.finalSequenceNumber(),
            AccountStateResponse.fromDomain(result.resultingState()),
            correlationId,
            result.emittedEvents() != null ? result.emittedEvents().size() : 0
        );
    }
}
