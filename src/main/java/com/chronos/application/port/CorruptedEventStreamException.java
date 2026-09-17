package com.chronos.application.port;

public class CorruptedEventStreamException extends RuntimeException {

    public CorruptedEventStreamException(String message) {
        super(message);
    }

    public CorruptedEventStreamException(String message, Throwable cause) {
        super(message, cause);
    }
}
