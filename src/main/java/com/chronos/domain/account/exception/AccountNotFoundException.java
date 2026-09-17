package com.chronos.domain.account.exception;

import java.util.UUID;

public class AccountNotFoundException extends DomainValidationException {

    public AccountNotFoundException(UUID accountId) {
        super("Account not found: " + accountId);
    }
}
