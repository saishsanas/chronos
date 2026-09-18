package com.chronos.domain.event.upcasting;

import com.chronos.domain.event.DomainEventEnvelope;
import com.chronos.domain.event.upcasting.exception.EventUpcastException;
import com.chronos.domain.event.upcasting.exception.MalformedEventPayloadException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class MoneyDepositedV1ToV2Upcaster implements EventUpcaster {

    public static final String DEFAULT_SOURCE = "MANUAL";

    @Override
    public String eventType() {
        return "MoneyDeposited";
    }

    @Override
    public int sourceVersion() {
        return 1;
    }

    @Override
    public int targetVersion() {
        return 2;
    }

    @Override
    public DomainEventEnvelope upcast(DomainEventEnvelope event) {
        Objects.requireNonNull(event, "event must not be null");

        if (!eventType().equals(event.eventType())) {
            throw new EventUpcastException(event.eventType(), event.eventVersion(), targetVersion(),
                "Upcaster " + getClass().getSimpleName() + " cannot handle event type: " + event.eventType());
        }

        if (event.eventVersion() != sourceVersion()) {
            throw new EventUpcastException(event.eventType(), event.eventVersion(), targetVersion(),
                "Expected sourceVersion " + sourceVersion() + " but got " + event.eventVersion());
        }

        JsonNode payload = event.payload();
        if (payload == null || !payload.isObject()) {
            throw new MalformedEventPayloadException(eventType(), sourceVersion(), "Payload must be a non-null JSON object");
        }

        JsonNode amountNode = payload.get("amountMinor");
        if (amountNode == null || !amountNode.isNumber() || amountNode.asLong() <= 0) {
            throw new MalformedEventPayloadException(eventType(), sourceVersion(), "Missing or non-positive amountMinor in payload");
        }

        // Pure in-memory transformation: clone JSON payload and inject deterministic default source
        ObjectNode evolvedPayload = ((ObjectNode) payload).deepCopy();
        if (!evolvedPayload.hasNonNull("source")) {
            evolvedPayload.put("source", DEFAULT_SOURCE);
        }

        return new DomainEventEnvelope(
            event.eventId(),
            event.aggregateId(),
            event.aggregateType(),
            event.sequenceNumber(),
            event.eventType(),
            targetVersion(),
            event.recordedAt(),
            event.metadata(),
            evolvedPayload
        );
    }
}
