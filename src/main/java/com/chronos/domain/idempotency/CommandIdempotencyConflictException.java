package com.chronos.domain.idempotency;

public class CommandIdempotencyConflictException extends RuntimeException {
    public CommandIdempotencyConflictException(String message) {
        super(message);
    }
}
