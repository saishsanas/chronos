package com.chronos.domain.account.exception;

public class TransactionLimitExceededException extends DomainValidationException {

    public TransactionLimitExceededException(String message) {
        super(message);
    }
}
