package com.chronos.domain.account.command;

import java.util.UUID;

public record DepositMoney(
    UUID accountId,
    long amountMinor
) implements AccountCommand {}
