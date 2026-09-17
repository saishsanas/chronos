package com.chronos.application.service;

import com.chronos.application.command.CommandContext;
import com.chronos.application.command.CommandResult;
import com.chronos.application.port.EventStore;
import com.chronos.domain.account.AccountReducer;
import com.chronos.domain.account.AccountState;
import com.chronos.domain.account.AccountStatus;
import com.chronos.domain.account.CorrectionDirection;
import com.chronos.domain.account.CorrectionType;
import com.chronos.domain.account.command.*;
import com.chronos.domain.account.exception.*;
import com.chronos.domain.event.DomainEventEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.chronos.application.port.SnapshotRepository;
import com.chronos.domain.snapshot.Snapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

@Service
public class AccountCommandProcessor {

    private final EventStore eventStore;
    private final ObjectMapper objectMapper;
    private final SnapshotRepository snapshotRepository;
    private final int snapshotInterval;

    public AccountCommandProcessor(EventStore eventStore, ObjectMapper objectMapper) {
        this(eventStore, objectMapper, null, 100);
    }

    @Autowired
    public AccountCommandProcessor(
            EventStore eventStore,
            ObjectMapper objectMapper,
            @Autowired(required = false) SnapshotRepository snapshotRepository,
            @Value("${chronos.snapshot.interval:100}") int snapshotInterval
    ) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.snapshotRepository = snapshotRepository;
        this.snapshotInterval = snapshotInterval > 0 ? snapshotInterval : 100;
    }


    public CommandResult process(AccountCommand command, CommandContext context) {
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(context, "context must not be null");

        UUID accountId = command.accountId();
        Instant commandTimestamp = Instant.now();

        // 1. Load full event stream from EventStore
        List<DomainEventEnvelope> stream = eventStore.loadStream(accountId);

        // 2. Reconstruct current state & validate stream integrity via AccountReducer
        AccountState currentState = AccountState.uninitialized(accountId);
        for (DomainEventEnvelope event : stream) {
            currentState = AccountReducer.reduce(currentState, event);
        }

        long expectedVersion = currentState.sequenceNumber();

        // 3. Validate command against current aggregate state & produce event envelope(s)
        List<DomainEventEnvelope> eventsToAppend = new ArrayList<>();
        long nextSequence = expectedVersion + 1;

        switch (command) {
            case CreateAccount cmd -> {
                if (currentState.status() != AccountStatus.UNINITIALIZED) {
                    throw new InvalidStateTransitionException("Account " + accountId + " is already initialized");
                }
                if (cmd.currency() == null || cmd.currency().isBlank()) {
                    throw new DomainValidationException("Currency must be a valid ISO-4217 code");
                }
                if (cmd.initialOverdraftLimitMinor() < 0) {
                    throw new DomainValidationException("Initial overdraft limit cannot be negative");
                }
                if (cmd.initialTransactionLimitMinor() <= 0) {
                    throw new DomainValidationException("Initial transaction limit must be positive");
                }

                ObjectNode payload = objectMapper.createObjectNode()
                        .put("currency", cmd.currency().trim().toUpperCase(Locale.ROOT))
                        .put("initialOverdraftLimitMinor", cmd.initialOverdraftLimitMinor())
                        .put("initialTransactionLimitMinor", cmd.initialTransactionLimitMinor());

                eventsToAppend.add(createEnvelope(
                        UUID.randomUUID(), accountId, nextSequence, "AccountCreated", 1, commandTimestamp, context, payload
                ));
            }

            case DepositMoney cmd -> {
                ensureInitialized(currentState);
                if (currentState.status() == AccountStatus.CLOSED) {
                    throw new InvalidStateTransitionException("Cannot deposit to a CLOSED account");
                }
                if (cmd.amountMinor() <= 0) {
                    throw new DomainValidationException("Deposit amount must be positive");
                }
                try {
                    long newBalance = Math.addExact(currentState.balanceMinor(), cmd.amountMinor());
                    ObjectNode payload = objectMapper.createObjectNode()
                            .put("amountMinor", cmd.amountMinor())
                            .put("currency", currentState.currency())
                            .put("resultingBalanceMinor", newBalance);

                    eventsToAppend.add(createEnvelope(
                            UUID.randomUUID(), accountId, nextSequence, "MoneyDeposited", 1, commandTimestamp, context, payload
                    ));
                } catch (ArithmeticException e) {
                    throw new DomainValidationException("Deposit amount causes monetary balance overflow", e);
                }
            }

            case WithdrawMoney cmd -> {
                ensureInitialized(currentState);
                if (currentState.status() == AccountStatus.FROZEN) {
                    throw new InvalidStateTransitionException("Cannot withdraw from a FROZEN account");
                }
                if (currentState.status() == AccountStatus.CLOSED) {
                    throw new InvalidStateTransitionException("Cannot withdraw from a CLOSED account");
                }
                if (cmd.amountMinor() <= 0) {
                    throw new DomainValidationException("Withdrawal amount must be positive");
                }
                if (cmd.amountMinor() > currentState.transactionLimitMinor()) {
                    throw new TransactionLimitExceededException(
                            "Withdrawal amount " + cmd.amountMinor() + " exceeds transaction limit " + currentState.transactionLimitMinor()
                    );
                }
                try {
                    long newBalance = Math.subtractExact(currentState.balanceMinor(), cmd.amountMinor());
                    if (newBalance < -currentState.overdraftLimitMinor()) {
                        throw new InsufficientFundsException(
                                "Withdrawal amount " + cmd.amountMinor() + " exceeds available balance + overdraft limit (" + currentState.overdraftLimitMinor() + ")"
                        );
                    }

                    ObjectNode payload = objectMapper.createObjectNode()
                            .put("amountMinor", cmd.amountMinor())
                            .put("currency", currentState.currency())
                            .put("resultingBalanceMinor", newBalance);

                    eventsToAppend.add(createEnvelope(
                            UUID.randomUUID(), accountId, nextSequence, "MoneyWithdrawn", 1, commandTimestamp, context, payload
                    ));
                } catch (ArithmeticException e) {
                    throw new DomainValidationException("Withdrawal causes monetary balance overflow", e);
                }
            }

            case FreezeAccount cmd -> {
                ensureInitialized(currentState);
                if (currentState.status() == AccountStatus.FROZEN) {
                    throw new InvalidStateTransitionException("Account " + accountId + " is already FROZEN");
                }
                if (currentState.status() == AccountStatus.CLOSED) {
                    throw new InvalidStateTransitionException("Cannot freeze a CLOSED account");
                }
                if (cmd.reason() == null || cmd.reason().isBlank()) {
                    throw new DomainValidationException("Freeze reason must not be empty");
                }

                ObjectNode payload = objectMapper.createObjectNode().put("reason", cmd.reason().trim());
                eventsToAppend.add(createEnvelope(
                        UUID.randomUUID(), accountId, nextSequence, "AccountFrozen", 1, commandTimestamp, context, payload
                ));
            }

            case UnfreezeAccount cmd -> {
                ensureInitialized(currentState);
                if (currentState.status() == AccountStatus.ACTIVE) {
                    throw new InvalidStateTransitionException("Account " + accountId + " is already ACTIVE");
                }
                if (currentState.status() == AccountStatus.CLOSED) {
                    throw new InvalidStateTransitionException("Cannot unfreeze a CLOSED account");
                }
                if (cmd.reason() == null || cmd.reason().isBlank()) {
                    throw new DomainValidationException("Unfreeze reason must not be empty");
                }

                ObjectNode payload = objectMapper.createObjectNode().put("reason", cmd.reason().trim());
                eventsToAppend.add(createEnvelope(
                        UUID.randomUUID(), accountId, nextSequence, "AccountUnfrozen", 1, commandTimestamp, context, payload
                ));
            }

            case SetOverdraftLimit cmd -> {
                ensureInitialized(currentState);
                if (currentState.status() == AccountStatus.CLOSED) {
                    throw new InvalidStateTransitionException("Cannot change overdraft limit on a CLOSED account");
                }
                if (cmd.newOverdraftLimitMinor() < 0) {
                    throw new DomainValidationException("Overdraft limit cannot be negative");
                }
                if (currentState.balanceMinor() < -cmd.newOverdraftLimitMinor()) {
                    throw new InsufficientFundsException(
                            "Current balance " + currentState.balanceMinor() + " violates proposed overdraft limit " + cmd.newOverdraftLimitMinor()
                    );
                }

                ObjectNode payload = objectMapper.createObjectNode()
                        .put("newOverdraftLimitMinor", cmd.newOverdraftLimitMinor())
                        .put("previousOverdraftLimitMinor", currentState.overdraftLimitMinor());

                eventsToAppend.add(createEnvelope(
                        UUID.randomUUID(), accountId, nextSequence, "OverdraftLimitChanged", 1, commandTimestamp, context, payload
                ));
            }

            case SetTransactionLimit cmd -> {
                ensureInitialized(currentState);
                if (currentState.status() == AccountStatus.CLOSED) {
                    throw new InvalidStateTransitionException("Cannot change transaction limit on a CLOSED account");
                }
                if (cmd.newTransactionLimitMinor() < 0) {
                    throw new DomainValidationException("Transaction limit cannot be negative");
                }

                ObjectNode payload = objectMapper.createObjectNode()
                        .put("newTransactionLimitMinor", cmd.newTransactionLimitMinor())
                        .put("previousTransactionLimitMinor", currentState.transactionLimitMinor());

                eventsToAppend.add(createEnvelope(
                        UUID.randomUUID(), accountId, nextSequence, "TransactionLimitChanged", 1, commandTimestamp, context, payload
                ));
            }

            case IssueCorrection cmd -> {
                ensureInitialized(currentState);
                if (currentState.status() == AccountStatus.CLOSED) {
                    throw new InvalidStateTransitionException("Cannot issue correction on a CLOSED account");
                }
                if (cmd.adjustmentAmountMinor() <= 0) {
                    throw new DomainValidationException("Correction adjustment amount must be positive");
                }
                if (cmd.reason() == null || cmd.reason().isBlank()) {
                    throw new DomainValidationException("Correction reason must not be empty");
                }
                if (cmd.targetEventId() == null) {
                    throw new InvalidCorrectionException("Target event ID must not be null");
                }

                // Verify target event exists in stream
                DomainEventEnvelope targetEvent = stream.stream()
                        .filter(e -> e.eventId().equals(cmd.targetEventId()))
                        .findFirst()
                        .orElseThrow(() -> new InvalidCorrectionException("Target event " + cmd.targetEventId() + " not found in stream for aggregate " + accountId));

                // Verify target event belongs to same aggregate
                if (!targetEvent.aggregateId().equals(accountId)) {
                    throw new InvalidCorrectionException("Target event " + cmd.targetEventId() + " does not belong to aggregate " + accountId);
                }

                // Verify target event is semantically correct for financial correction
                if ("AccountFrozen".equals(targetEvent.eventType()) || "AccountUnfrozen".equals(targetEvent.eventType()) || "AccountClosed".equals(targetEvent.eventType())) {
                    throw new InvalidCorrectionException("Target event '" + targetEvent.eventType() + "' is not a monetary transaction and cannot be corrected");
                }

                // Verify target event has not already been reversed
                boolean alreadyReversed = stream.stream()
                        .filter(e -> "CorrectionIssued".equals(e.eventType()))
                        .anyMatch(e -> cmd.targetEventId().toString().equals(e.getPayloadString("targetEventId"))
                                && "REVERSAL".equalsIgnoreCase(e.getPayloadString("correctionType")));

                if (alreadyReversed) {
                    throw new InvalidCorrectionException("Target event " + cmd.targetEventId() + " has already been reversed");
                }

                try {
                    long delta = cmd.direction() == CorrectionDirection.CREDIT ? cmd.adjustmentAmountMinor() : -cmd.adjustmentAmountMinor();
                    long newBalance = Math.addExact(currentState.balanceMinor(), delta);
                    if (newBalance < -currentState.overdraftLimitMinor()) {
                        throw new InsufficientFundsException("Correction resulting balance " + newBalance + " violates overdraft limit " + currentState.overdraftLimitMinor());
                    }

                    ObjectNode payload = objectMapper.createObjectNode()
                            .put("targetEventId", cmd.targetEventId().toString())
                            .put("correctionType", cmd.correctionType().name())
                            .put("direction", cmd.direction().name())
                            .put("adjustmentAmountMinor", cmd.adjustmentAmountMinor())
                            .put("reason", cmd.reason().trim());

                    eventsToAppend.add(createEnvelope(
                            UUID.randomUUID(), accountId, nextSequence, "CorrectionIssued", 1, commandTimestamp, context, payload
                    ));
                } catch (ArithmeticException e) {
                    throw new DomainValidationException("Correction causes monetary balance overflow", e);
                }
            }

            case CloseAccount cmd -> {
                ensureInitialized(currentState);
                if (currentState.status() == AccountStatus.CLOSED) {
                    throw new InvalidStateTransitionException("Account " + accountId + " is already CLOSED");
                }
                if (cmd.reason() == null || cmd.reason().isBlank()) {
                    throw new DomainValidationException("Close reason must not be empty");
                }
                if (currentState.balanceMinor() != 0L) {
                    throw new DomainValidationException("Cannot close account with non-zero balance: " + currentState.balanceMinor());
                }

                ObjectNode payload = objectMapper.createObjectNode().put("reason", cmd.reason().trim());
                eventsToAppend.add(createEnvelope(
                        UUID.randomUUID(), accountId, nextSequence, "AccountClosed", 1, commandTimestamp, context, payload
                ));
            }
        }

        // 4. Append emitted events atomically to EventStore using expected version
        eventStore.append(accountId, expectedVersion, eventsToAppend);

        // 5. Re-apply emitted events to currentState to produce exact resulting state
        AccountState resultingState = currentState;
        for (DomainEventEnvelope event : eventsToAppend) {
            resultingState = AccountReducer.reduce(resultingState, event);
        }

        // 6. Non-blocking automatic snapshot creation if threshold reached
        if (snapshotRepository != null && resultingState.sequenceNumber() > 0 && resultingState.sequenceNumber() % snapshotInterval == 0) {
            try {
                Snapshot snapshot = Snapshot.create(resultingState);
                snapshotRepository.save(snapshot);
            } catch (Exception e) {
                // Fail-safe: Event store is source of truth, snapshot save failure must not invalidate command execution
            }
        }

        return new CommandResult(accountId, resultingState, Collections.unmodifiableList(eventsToAppend), resultingState.sequenceNumber());
    }


    private void ensureInitialized(AccountState state) {
        if (state.status() == AccountStatus.UNINITIALIZED) {
            throw new AccountNotFoundException(state.accountId());
        }
    }

    private DomainEventEnvelope createEnvelope(
            UUID eventId, UUID aggregateId, long sequenceNumber,
            String eventType, int eventVersion, Instant recordedAt,
            CommandContext context, ObjectNode payload
    ) {
        return new DomainEventEnvelope(
                eventId,
                aggregateId,
                "Account",
                sequenceNumber,
                eventType,
                eventVersion,
                recordedAt,
                context.toMetadata(),
                payload
        );
    }
}
