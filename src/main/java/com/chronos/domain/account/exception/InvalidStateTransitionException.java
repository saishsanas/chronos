package com.chronos.domain.account.exception;

public class InvalidStateTransitionException extends DomainValidationException {

    public InvalidStateTransitionException(String message) {
        super(message);
    }
}
