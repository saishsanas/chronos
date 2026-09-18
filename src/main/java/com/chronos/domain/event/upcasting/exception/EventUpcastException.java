package com.chronos.domain.event.upcasting.exception;

public class EventUpcastException extends EventSchemaException {

    private final String eventType;
    private final int fromVersion;
    private final int toVersion;

    public EventUpcastException(String eventType, int fromVersion, int toVersion, String message) {
        super("Failed to upcast event '" + eventType + "' from v" + fromVersion + " to v" + toVersion + ": " + message);
        this.eventType = eventType;
        this.fromVersion = fromVersion;
        this.toVersion = toVersion;
    }

    public EventUpcastException(String eventType, int fromVersion, int toVersion, String message, Throwable cause) {
        super("Failed to upcast event '" + eventType + "' from v" + fromVersion + " to v" + toVersion + ": " + message, cause);
        this.eventType = eventType;
        this.fromVersion = fromVersion;
        this.toVersion = toVersion;
    }

    public String eventType() {
        return eventType;
    }

    public int fromVersion() {
        return fromVersion;
    }

    public int toVersion() {
        return toVersion;
    }
}
