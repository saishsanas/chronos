package com.chronos.domain.account.command;

import java.util.UUID;

public record SetOverdraftLimit(
    UUID accountId,
    long newOverdraftLimitMinor
) implements AccountCommand {}
