package com.chronos.domain.account.command;

import java.util.UUID;

public record CreateAccount(
    UUID accountId,
    String currency,
    long initialOverdraftLimitMinor,
    long initialTransactionLimitMinor
) implements AccountCommand {}
