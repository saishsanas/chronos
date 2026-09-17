package com.chronos.api.dto;

import com.chronos.domain.account.AccountState;
import com.chronos.domain.account.AccountStatus;
import com.chronos.domain.projection.AccountSummaryProjection;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AccountSummaryResponse(
    UUID accountId,
    String currency,
    long balanceMinor,
    long overdraftLimitMinor,
    long transactionLimitMinor,
    AccountStatus status,
    long sequenceNumber,
    Instant lastUpdatedAt
) {
    public static AccountSummaryResponse fromDomain(AccountState state) {
        if (state == null) return null;
        return new AccountSummaryResponse(
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

    public static AccountSummaryResponse fromProjection(AccountSummaryProjection proj) {
        if (proj == null) return null;
        return new AccountSummaryResponse(
            proj.accountId(),
            proj.currency(),
            proj.balanceMinor(),
            proj.overdraftLimitMinor(),
            proj.transactionLimitMinor(),
            proj.status(),
            proj.sequenceNumber(),
            proj.lastUpdatedAt()
        );
    }
}
