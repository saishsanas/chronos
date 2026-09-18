package com.chronos.domain.event.upcasting.exception;

public class MalformedEventPayloadException extends EventSchemaException {

    private final String eventType;
    private final int eventVersion;

    public MalformedEventPayloadException(String eventType, int eventVersion, String message) {
        super("Malformed payload for event '" + eventType + "' (v" + eventVersion + "): " + message);
        this.eventType = eventType;
        this.eventVersion = eventVersion;
    }

    public MalformedEventPayloadException(String eventType, int eventVersion, String message, Throwable cause) {
        super("Malformed payload for event '" + eventType + "' (v" + eventVersion + "): " + message, cause);
        this.eventType = eventType;
        this.eventVersion = eventVersion;
    }

    public String eventType() {
        return eventType;
    }

    public int eventVersion() {
        return eventVersion;
    }
}
