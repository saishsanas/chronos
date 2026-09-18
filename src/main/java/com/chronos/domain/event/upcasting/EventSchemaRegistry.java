package com.chronos.domain.event.upcasting;

import com.chronos.domain.event.upcasting.exception.UnknownEventTypeException;
import com.chronos.domain.event.upcasting.exception.UnsupportedEventVersionException;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

@Component
public class EventSchemaRegistry {

    public record EventSchemaInfo(
        String eventType,
        int currentVersion,
        Set<Integer> supportedVersions
    ) {
        public EventSchemaInfo {
            supportedVersions = Collections.unmodifiableSet(supportedVersions);
        }
    }

    private static final Map<String, EventSchemaInfo> SCHEMAS = Map.of(
        "AccountCreated", new EventSchemaInfo("AccountCreated", 1, Set.of(1)),
        "MoneyDeposited", new EventSchemaInfo("MoneyDeposited", 2, Set.of(1, 2)),
        "MoneyWithdrawn", new EventSchemaInfo("MoneyWithdrawn", 1, Set.of(1)),
        "AccountFrozen", new EventSchemaInfo("AccountFrozen", 1, Set.of(1)),
        "AccountUnfrozen", new EventSchemaInfo("AccountUnfrozen", 1, Set.of(1)),
        "OverdraftLimitChanged", new EventSchemaInfo("OverdraftLimitChanged", 1, Set.of(1)),
        "TransactionLimitChanged", new EventSchemaInfo("TransactionLimitChanged", 1, Set.of(1)),
        "CorrectionIssued", new EventSchemaInfo("CorrectionIssued", 1, Set.of(1)),
        "AccountClosed", new EventSchemaInfo("AccountClosed", 1, Set.of(1))
    );

    private static final EventSchemaRegistry INSTANCE = new EventSchemaRegistry();

    public static EventSchemaRegistry getInstance() {
        return INSTANCE;
    }

    public boolean isKnownEventType(String eventType) {
        return eventType != null && SCHEMAS.containsKey(eventType);
    }

    public Set<String> getKnownEventTypes() {
        return SCHEMAS.keySet();
    }

    public int getCurrentVersion(String eventType) {
        EventSchemaInfo info = SCHEMAS.get(eventType);
        if (info == null) {
            throw new UnknownEventTypeException(eventType);
        }
        return info.currentVersion();
    }

    public boolean isSupportedVersion(String eventType, int version) {
        EventSchemaInfo info = SCHEMAS.get(eventType);
        if (info == null) {
            return false;
        }
        return info.supportedVersions().contains(version);
    }

    public void validateVersion(String eventType, int version) {
        if (eventType == null || !SCHEMAS.containsKey(eventType)) {
            throw new UnknownEventTypeException(eventType);
        }
        if (version < 1) {
            throw new UnsupportedEventVersionException(eventType, version, "Event version must be >= 1, but got " + version);
        }
        EventSchemaInfo info = SCHEMAS.get(eventType);
        if (version > info.currentVersion()) {
            throw new UnsupportedEventVersionException(
                eventType, version,
                "Unsupported future event version: " + version + " exceeds current version " + info.currentVersion() + " for event type '" + eventType + "'"
            );
        }
        if (!info.supportedVersions().contains(version)) {
            throw new UnsupportedEventVersionException(
                eventType, version,
                "Unsupported historical event version: " + version + " for event type '" + eventType + "'"
            );
        }
    }
}
