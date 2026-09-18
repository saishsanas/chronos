package com.chronos.domain.event.upcasting.exception;

public class UnsupportedEventVersionException extends EventSchemaException {

    private final String eventType;
    private final int eventVersion;

    public UnsupportedEventVersionException(String eventType, int eventVersion) {
        super("Unsupported event version " + eventVersion + " for event type '" + eventType + "'");
        this.eventType = eventType;
        this.eventVersion = eventVersion;
    }

    public UnsupportedEventVersionException(String eventType, int eventVersion, String message) {
        super(message);
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
