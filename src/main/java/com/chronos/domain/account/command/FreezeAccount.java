package com.chronos.domain.account.command;

import java.util.UUID;

public record FreezeAccount(
    UUID accountId,
    String reason
) implements AccountCommand {}
