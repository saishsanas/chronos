package com.chronos.domain.projection;

import com.chronos.domain.account.AccountState;
import com.chronos.domain.account.AccountStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AccountSummaryProjection(
    UUID accountId,
    String currency,
    long balanceMinor,
    long overdraftLimitMinor,
    long transactionLimitMinor,
    AccountStatus status,
    long sequenceNumber,
    Instant lastUpdatedAt,
    Instant projectedAt
) {
    public AccountSummaryProjection {
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(lastUpdatedAt, "lastUpdatedAt must not be null");
        Objects.requireNonNull(projectedAt, "projectedAt must not be null");
    }

    public static AccountSummaryProjection fromAccountState(AccountState state, Instant projectedAt) {
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(projectedAt, "projectedAt must not be null");
        return new AccountSummaryProjection(
            state.accountId(),
            state.currency(),
            state.balanceMinor(),
            state.overdraftLimitMinor(),
            state.transactionLimitMinor(),
            state.status(),
            state.sequenceNumber(),
            state.lastUpdatedAt(),
            projectedAt
        );
    }

    public AccountState toAccountState() {
        return new AccountState(
            accountId,
            currency,
            balanceMinor,
            overdraftLimitMinor,
            transactionLimitMinor,
            status,
            sequenceNumber,
            lastUpdatedAt
        );
    }
}
