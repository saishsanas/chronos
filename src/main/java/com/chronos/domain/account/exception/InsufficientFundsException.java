package com.chronos.domain.account.exception;

public class InsufficientFundsException extends DomainValidationException {

    public InsufficientFundsException(String message) {
        super(message);
    }
}
