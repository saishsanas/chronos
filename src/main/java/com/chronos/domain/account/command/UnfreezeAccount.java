package com.chronos.domain.account.command;

import java.util.UUID;

public record UnfreezeAccount(
    UUID accountId,
    String reason
) implements AccountCommand {}
