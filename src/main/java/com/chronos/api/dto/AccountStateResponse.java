package com.chronos.api.dto;

import com.chronos.domain.account.AccountState;
import com.chronos.domain.account.AccountStatus;
import java.time.Instant;
import java.util.UUID;

public record AccountStateResponse(
    UUID accountId,
    String currency,
    long balanceMinor,
    long overdraftLimitMinor,
    long transactionLimitMinor,
    AccountStatus status,
    long sequenceNumber,
    Instant lastUpdatedAt
) {
    public static AccountStateResponse fromDomain(AccountState state) {
        if (state == null) return null;
        return new AccountStateResponse(
            state.accountId(),
            state.currency(),
            state.balanceMinor(),
            state.overdraftLimitMinor(),
            state.transactionLimitMinor(),
            state.status(),
            state.sequenceNumber(),
            state.lastUpdatedAt()
        );
    }
}
