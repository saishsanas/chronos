package com.chronos.application.port;

public class ConsumerEventIntegrityException extends RuntimeException {
    public ConsumerEventIntegrityException(String message) {
        super(message);
    }

    public ConsumerEventIntegrityException(String message, Throwable cause) {
        super(message, cause);
    }
}
