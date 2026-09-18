package com.chronos.domain.event.upcasting;

import com.chronos.domain.event.DomainEventEnvelope;
import com.chronos.domain.event.upcasting.exception.EventSchemaException;
import com.chronos.domain.event.upcasting.exception.EventUpcastException;
import com.chronos.domain.event.upcasting.exception.UnknownEventTypeException;
import com.chronos.domain.event.upcasting.exception.UnsupportedEventVersionException;
import com.chronos.infrastructure.observability.ChronosMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class EventUpcasterRegistry {

    private static final Logger log = LoggerFactory.getLogger(EventUpcasterRegistry.class);

    private final EventSchemaRegistry schemaRegistry;
    private final ChronosMetrics metrics;
    private final Map<String, Map<Integer, EventUpcaster>> upcasters = new ConcurrentHashMap<>();

    private static final EventUpcasterRegistry INSTANCE = new EventUpcasterRegistry();

    public static EventUpcasterRegistry getInstance() {
        return INSTANCE;
    }

    public EventUpcasterRegistry() {
        this(EventSchemaRegistry.getInstance(), List.of(new MoneyDepositedV1ToV2Upcaster()), null);
    }

    public EventUpcasterRegistry(EventSchemaRegistry schemaRegistry) {
        this(schemaRegistry, List.of(new MoneyDepositedV1ToV2Upcaster()), null);
    }

    @Autowired
    public EventUpcasterRegistry(
        EventSchemaRegistry schemaRegistry,
        List<EventUpcaster> upcasterList,
        @Autowired(required = false) ChronosMetrics metrics
    ) {
        this.schemaRegistry = Objects.requireNonNull(schemaRegistry, "schemaRegistry must not be null");
        this.metrics = metrics;
        if (upcasterList != null) {
            for (EventUpcaster upcaster : upcasterList) {
                register(upcaster);
            }
        }
    }

    public synchronized void register(EventUpcaster upcaster) {
        Objects.requireNonNull(upcaster, "upcaster must not be null");
        upcasters.computeIfAbsent(upcaster.eventType(), k -> new ConcurrentHashMap<>())
                 .put(upcaster.sourceVersion(), upcaster);
    }

    public DomainEventEnvelope upcastToCanonical(DomainEventEnvelope raw) {
        if (raw == null) {
            throw new IllegalArgumentException("DomainEventEnvelope must not be null");
        }

        String eventType = raw.eventType();
        int storedVersion = raw.eventVersion();

        // 1. Resolve event type & validate against schema registry
        try {
            schemaRegistry.validateVersion(eventType, storedVersion);
        } catch (UnknownEventTypeException e) {
            if (metrics != null) metrics.recordUnknownEventType(eventType);
            throw e;
        } catch (UnsupportedEventVersionException e) {
            if (metrics != null) metrics.recordUnsupportedEventVersion(eventType, storedVersion);
            throw e;
        }

        int targetVersion = schemaRegistry.getCurrentVersion(eventType);

        // 2. Already at canonical current version
        if (storedVersion == targetVersion) {
            return raw;
        }

        // 3. Sequentially upcast through the version chain (v1 -> v2 -> v3 ...)
        DomainEventEnvelope current = raw;
        while (current.eventVersion() < targetVersion) {
            int currentVer = current.eventVersion();
            Map<Integer, EventUpcaster> typeUpcasters = upcasters.get(eventType);
            EventUpcaster upcaster = typeUpcasters != null ? typeUpcasters.get(currentVer) : null;

            if (upcaster == null) {
                String msg = "No upcaster registered for event '" + eventType + "' from v" + currentVer + " to v" + (currentVer + 1);
                if (metrics != null) metrics.recordUnsupportedEventVersion(eventType, currentVer);
                throw new UnsupportedEventVersionException(eventType, currentVer, msg);
            }

            try {
                DomainEventEnvelope next = upcaster.upcast(current);
                if (next == null) {
                    throw new EventUpcastException(eventType, currentVer, upcaster.targetVersion(), "Upcaster returned null envelope");
                }
                if (next.eventVersion() != upcaster.targetVersion()) {
                    throw new EventUpcastException(eventType, currentVer, upcaster.targetVersion(),
                        "Upcaster produced envelope with version " + next.eventVersion() + " instead of expected " + upcaster.targetVersion());
                }
                if (metrics != null) {
                    metrics.recordEventUpcast(eventType, currentVer, next.eventVersion());
                }
                current = next;
            } catch (EventSchemaException e) {
                if (metrics != null) metrics.recordEventUpcastFailure(eventType);
                throw e;
            } catch (Exception e) {
                if (metrics != null) metrics.recordEventUpcastFailure(eventType);
                throw new EventUpcastException(eventType, currentVer, upcaster.targetVersion(), e.getMessage(), e);
            }
        }

        return current;
    }

    public EventSchemaRegistry getSchemaRegistry() {
        return schemaRegistry;
    }
}
