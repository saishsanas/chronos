package com.chronos.domain.event.upcasting.exception;

import com.chronos.application.port.CorruptedEventStreamException;

public class EventSchemaException extends CorruptedEventStreamException {

    public EventSchemaException(String message) {
        super(message);
    }

    public EventSchemaException(String message, Throwable cause) {
        super(message, cause);
    }
}
