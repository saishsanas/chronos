package com.chronos.domain.event.upcasting.exception;

public class UnknownEventTypeException extends EventSchemaException {

    private final String eventType;

    public UnknownEventTypeException(String eventType) {
        super("Unknown event type: '" + eventType + "'");
        this.eventType = eventType;
    }

    public String eventType() {
        return eventType;
    }
}
