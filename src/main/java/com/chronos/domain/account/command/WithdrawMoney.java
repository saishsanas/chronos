package com.chronos.domain.account.command;

import java.util.UUID;

public record WithdrawMoney(
    UUID accountId,
    long amountMinor
) implements AccountCommand {}
