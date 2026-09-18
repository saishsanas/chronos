package com.chronos.domain.account.command;

import java.util.UUID;

public record DepositMoney(
    UUID accountId,
    long amountMinor,
    String source
) implements AccountCommand {

    public DepositMoney(UUID accountId, long amountMinor) {
        this(accountId, amountMinor, "MANUAL");
    }
}
