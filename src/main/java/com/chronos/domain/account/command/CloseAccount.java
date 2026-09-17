package com.chronos.domain.account.command;

import java.util.UUID;

public record CloseAccount(
    UUID accountId,
    String reason
) implements AccountCommand {}
