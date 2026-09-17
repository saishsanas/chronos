package com.chronos.application.port;

public class OptimisticConcurrencyException extends RuntimeException {
    
    public OptimisticConcurrencyException(String message) {
        super(message);
    }

    public OptimisticConcurrencyException(String message, Throwable cause) {
        super(message, cause);
    }
}
