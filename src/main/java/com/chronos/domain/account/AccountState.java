package com.chronos.domain.account;

import java.time.Instant;
import java.util.UUID;

public record AccountState(
    UUID accountId,
    String currency,
    long balanceMinor,
    long overdraftLimitMinor,
    long transactionLimitMinor,
    AccountStatus status,
    long sequenceNumber,
    Instant lastUpdatedAt
) {
    public static AccountState uninitialized(UUID accountId) {
        return new AccountState(accountId, "INR", 0L, 0L, 0L, AccountStatus.UNINITIALIZED, 0L, Instant.EPOCH);
    }
}
