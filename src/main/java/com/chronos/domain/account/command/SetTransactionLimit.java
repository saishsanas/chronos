package com.chronos.domain.account.command;

import java.util.UUID;

public record SetTransactionLimit(
    UUID accountId,
    long newTransactionLimitMinor
) implements AccountCommand {}
