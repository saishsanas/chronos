package com.chronos.domain.account;

import com.chronos.application.port.CorruptedEventStreamException;
import com.chronos.domain.event.DomainEventEnvelope;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class AccountReducer {

    private AccountReducer() {
        // Pure static utility class
    }

    public static AccountState reduce(AccountState current, DomainEventEnvelope event) {
        Objects.requireNonNull(current, "current state must not be null");
        Objects.requireNonNull(event, "event must not be null");

        // 1. Validate Aggregate Identity
        if (current.status() != AccountStatus.UNINITIALIZED && !current.accountId().equals(event.aggregateId())) {
            throw new CorruptedEventStreamException(
                "Aggregate ID mismatch: expected " + current.accountId() + " but got " + event.aggregateId()
            );
        }

        // 2. Validate Aggregate Type
        if (!"Account".equals(event.aggregateType())) {
            throw new CorruptedEventStreamException(
                "Invalid aggregate type: expected 'Account' but got '" + event.aggregateType() + "'"
            );
        }

        // 3. Validate Sequence Continuity
        if (current.sequenceNumber() == 0) {
            if (event.sequenceNumber() != 1) {
                throw new CorruptedEventStreamException(
                    "First event in stream must have sequence 1, but got sequence " + event.sequenceNumber()
                );
            }
            if (!"AccountCreated".equals(event.eventType())) {
                throw new CorruptedEventStreamException(
                    "First event in stream must be 'AccountCreated', but got '" + event.eventType() + "'"
                );
            }
        } else {
            if (event.sequenceNumber() != current.sequenceNumber() + 1) {
                throw new CorruptedEventStreamException(
                    "Sequence gap or regression detected: current sequence " + current.sequenceNumber() +
                    " -> event sequence " + event.sequenceNumber()
                );
            }
        }

        // 4. Validate Event Schema Version
        if (event.eventVersion() != 1) {
            throw new CorruptedEventStreamException(
                "Unsupported event version: " + event.eventVersion() + " for event type '" + event.eventType() + "'"
            );
        }

        // 5. Validate Timestamp Monotonicity (if aggregate has prior history)
        if (current.sequenceNumber() > 0 && event.recordedAt().isBefore(current.lastUpdatedAt())) {
            throw new CorruptedEventStreamException(
                "Event timestamp regression: recordedAt " + event.recordedAt() +
                " is before previous event timestamp " + current.lastUpdatedAt()
            );
        }

        long newSequence = event.sequenceNumber();
        Instant timestamp = event.recordedAt();

        try {
            return switch (event.eventType()) {
                case "AccountCreated" -> {
                    if (current.status() != AccountStatus.UNINITIALIZED) {
                        throw new CorruptedEventStreamException("Cannot apply AccountCreated to an already initialized account");
                    }
                    String currency = event.getPayloadString("currency");
                    if (currency == null || currency.isBlank()) {
                        throw new CorruptedEventStreamException("AccountCreated event missing currency payload");
                    }
                    long overdraft = event.getPayloadLong("initialOverdraftLimitMinor");
                    long txLimit = event.getPayloadLong("initialTransactionLimitMinor");
                    yield new AccountState(
                        event.aggregateId(),
                        currency,
                        0L, // Balance initializes to 0
                        overdraft,
                        txLimit,
                        AccountStatus.ACTIVE,
                        newSequence,
                        timestamp
                    );
                }
                case "MoneyDeposited" -> {
                    if (current.status() != AccountStatus.ACTIVE && current.status() != AccountStatus.FROZEN) {
                        throw new CorruptedEventStreamException("Cannot apply MoneyDeposited to an account in status " + current.status());
                    }
                    long amount = event.getPayloadLong("amountMinor");
                    long newBalance = Math.addExact(current.balanceMinor(), amount);
                    yield new AccountState(
                        current.accountId(),
                        current.currency(),
                        newBalance,
                        current.overdraftLimitMinor(),
                        current.transactionLimitMinor(),
                        current.status(),
                        newSequence,
                        timestamp
                    );
                }
                case "MoneyWithdrawn" -> {
                    if (current.status() != AccountStatus.ACTIVE) {
                        throw new CorruptedEventStreamException("Cannot apply MoneyWithdrawn to an account in status " + current.status());
                    }
                    long amount = event.getPayloadLong("amountMinor");
                    long newBalance = Math.subtractExact(current.balanceMinor(), amount);
                    yield new AccountState(
                        current.accountId(),
                        current.currency(),
                        newBalance,
                        current.overdraftLimitMinor(),
                        current.transactionLimitMinor(),
                        current.status(),
                        newSequence,
                        timestamp
                    );
                }
                case "AccountFrozen" -> {
                    if (current.status() != AccountStatus.ACTIVE) {
                        throw new CorruptedEventStreamException("Cannot apply AccountFrozen to an account in status " + current.status());
                    }
                    yield new AccountState(
                        current.accountId(),
                        current.currency(),
                        current.balanceMinor(),
                        current.overdraftLimitMinor(),
                        current.transactionLimitMinor(),
                        AccountStatus.FROZEN,
                        newSequence,
                        timestamp
                    );
                }
                case "AccountUnfrozen" -> {
                    if (current.status() != AccountStatus.FROZEN) {
                        throw new CorruptedEventStreamException("Cannot apply AccountUnfrozen to an account in status " + current.status());
                    }
                    yield new AccountState(
                        current.accountId(),
                        current.currency(),
                        current.balanceMinor(),
                        current.overdraftLimitMinor(),
                        current.transactionLimitMinor(),
                        AccountStatus.ACTIVE,
                        newSequence,
                        timestamp
                    );
                }
                case "OverdraftLimitChanged" -> {
                    if (current.status() == AccountStatus.CLOSED) {
                        throw new CorruptedEventStreamException("Cannot change overdraft limit on a CLOSED account");
                    }
                    long newLimit = event.getPayloadLong("newOverdraftLimitMinor");
                    yield new AccountState(
                        current.accountId(),
                        current.currency(),
                        current.balanceMinor(),
                        newLimit,
                        current.transactionLimitMinor(),
                        current.status(),
                        newSequence,
                        timestamp
                    );
                }
                case "TransactionLimitChanged" -> {
                    if (current.status() == AccountStatus.CLOSED) {
                        throw new CorruptedEventStreamException("Cannot change transaction limit on a CLOSED account");
                    }
                    long newLimit = event.getPayloadLong("newTransactionLimitMinor");
                    yield new AccountState(
                        current.accountId(),
                        current.currency(),
                        current.balanceMinor(),
                        current.overdraftLimitMinor(),
                        newLimit,
                        current.status(),
                        newSequence,
                        timestamp
                    );
                }
                case "CorrectionIssued" -> {
                    if (current.status() == AccountStatus.CLOSED) {
                        throw new CorruptedEventStreamException("Cannot apply CorrectionIssued to a CLOSED account");
                    }
                    String direction = event.getPayloadString("direction");
                    long amount = event.getPayloadLong("adjustmentAmountMinor");
                    long delta = "CREDIT".equalsIgnoreCase(direction) ? amount : -amount;
                    long newBalance = Math.addExact(current.balanceMinor(), delta);
                    yield new AccountState(
                        current.accountId(),
                        current.currency(),
                        newBalance,
                        current.overdraftLimitMinor(),
                        current.transactionLimitMinor(),
                        current.status(),
                        newSequence,
                        timestamp
                    );
                }
                case "AccountClosed" -> {
                    if (current.status() == AccountStatus.CLOSED) {
                        throw new CorruptedEventStreamException("Cannot apply AccountClosed to an already CLOSED account");
                    }
                    yield new AccountState(
                        current.accountId(),
                        current.currency(),
                        current.balanceMinor(),
                        current.overdraftLimitMinor(),
                        current.transactionLimitMinor(),
                        AccountStatus.CLOSED,
                        newSequence,
                        timestamp
                    );
                }
                default -> throw new CorruptedEventStreamException("Unknown event type: '" + event.eventType() + "'");
            };
        } catch (ArithmeticException e) {
            throw new CorruptedEventStreamException("Monetary arithmetic overflow reducing event sequence " + newSequence, e);
        }
    }
}
