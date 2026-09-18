package com.chronos.domain.event.upcasting;

import com.chronos.domain.event.DomainEventEnvelope;
import com.chronos.domain.event.EventMetadata;
import com.chronos.domain.event.upcasting.exception.MalformedEventPayloadException;
import com.chronos.domain.event.upcasting.exception.UnknownEventTypeException;
import com.chronos.domain.event.upcasting.exception.UnsupportedEventVersionException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventUpcasterRegistryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private EventUpcasterRegistry registry;
    private final UUID aggregateId = UUID.randomUUID();
    private final UUID eventId = UUID.randomUUID();
    private final Instant recordedAt = Instant.parse("2026-09-18T10:00:00Z");
    private final EventMetadata metadata = new EventMetadata(UUID.randomUUID(), UUID.randomUUID(), "test-user", "idemp-01");

    @BeforeEach
    void setUp() {
        registry = new EventUpcasterRegistry();
    }

    private DomainEventEnvelope createRawV1Deposit(long amountMinor) {
        ObjectNode payload = objectMapper.createObjectNode()
                .put("amountMinor", amountMinor)
                .put("currency", "INR")
                .put("resultingBalanceMinor", amountMinor);

        return new DomainEventEnvelope(
                eventId, aggregateId, "Account", 2L, "MoneyDeposited", 1, recordedAt, metadata, payload
        );
    }

    @Test
    @DisplayName("1. Valid v1 MoneyDeposited is deterministically upcasted to canonical v2 with default source")
    void testValidV1ToV2Upcast() {
        DomainEventEnvelope v1 = createRawV1Deposit(50000L);
        assertThat(v1.eventVersion()).isEqualTo(1);
        assertThat(v1.payload().has("source")).isFalse();

        DomainEventEnvelope canonical = registry.upcastToCanonical(v1);

        assertThat(canonical.eventId()).isEqualTo(v1.eventId());
        assertThat(canonical.aggregateId()).isEqualTo(v1.aggregateId());
        assertThat(canonical.aggregateType()).isEqualTo(v1.aggregateType());
        assertThat(canonical.sequenceNumber()).isEqualTo(v1.sequenceNumber());
        assertThat(canonical.eventType()).isEqualTo("MoneyDeposited");
        assertThat(canonical.eventVersion()).isEqualTo(2);
        assertThat(canonical.recordedAt()).isEqualTo(v1.recordedAt());
        assertThat(canonical.metadata()).isEqualTo(v1.metadata());

        // Payload checks
        assertThat(canonical.payload().get("amountMinor").asLong()).isEqualTo(50000L);
        assertThat(canonical.payload().get("currency").asText()).isEqualTo("INR");
        assertThat(canonical.payload().get("resultingBalanceMinor").asLong()).isEqualTo(50000L);
        assertThat(canonical.payload().get("source").asText()).isEqualTo("MANUAL");
    }

    @Test
    @DisplayName("2. Already-current v2 event passes through unchanged")
    void testAlreadyCurrentV2PassesThrough() {
        ObjectNode payload = objectMapper.createObjectNode()
                .put("amountMinor", 75000L)
                .put("currency", "USD")
                .put("resultingBalanceMinor", 75000L)
                .put("source", "ATM");

        DomainEventEnvelope v2 = new DomainEventEnvelope(
                eventId, aggregateId, "Account", 2L, "MoneyDeposited", 2, recordedAt, metadata, payload
        );

        DomainEventEnvelope result = registry.upcastToCanonical(v2);

        assertThat(result).isSameAs(v2);
        assertThat(result.eventVersion()).isEqualTo(2);
        assertThat(result.payload().get("source").asText()).isEqualTo("ATM");
    }

    @Test
    @DisplayName("3. Upcasting is pure and deterministic: repeated calls produce identical representations")
    void testUpcastingIsPureAndDeterministic() {
        DomainEventEnvelope v1 = createRawV1Deposit(120000L);

        DomainEventEnvelope run1 = registry.upcastToCanonical(v1);
        DomainEventEnvelope run2 = registry.upcastToCanonical(v1);

        assertThat(run1).isEqualTo(run2);
        assertThat(run1.payload().toString()).isEqualTo(run2.payload().toString());
    }

    @Test
    @DisplayName("4. Malformed legacy payload: missing or non-positive amountMinor throws MalformedEventPayloadException")
    void testMalformedLegacyPayload() {
        // Missing amountMinor
        ObjectNode missingAmount = objectMapper.createObjectNode().put("currency", "INR");
        DomainEventEnvelope eMissing = new DomainEventEnvelope(
                UUID.randomUUID(), aggregateId, "Account", 2L, "MoneyDeposited", 1, recordedAt, metadata, missingAmount
        );
        assertThatThrownBy(() -> registry.upcastToCanonical(eMissing))
                .isInstanceOf(MalformedEventPayloadException.class)
                .hasMessageContaining("Missing or non-positive amountMinor");

        // Non-positive amountMinor
        ObjectNode negativeAmount = objectMapper.createObjectNode().put("amountMinor", -100L);
        DomainEventEnvelope eNegative = new DomainEventEnvelope(
                UUID.randomUUID(), aggregateId, "Account", 2L, "MoneyDeposited", 1, recordedAt, metadata, negativeAmount
        );
        assertThatThrownBy(() -> registry.upcastToCanonical(eNegative))
                .isInstanceOf(MalformedEventPayloadException.class)
                .hasMessageContaining("Missing or non-positive amountMinor");
    }

    @Test
    @DisplayName("5. Unsupported future event version throws UnsupportedEventVersionException")
    void testUnsupportedFutureVersion() {
        ObjectNode payload = objectMapper.createObjectNode().put("amountMinor", 1000L);
        DomainEventEnvelope futureEvent = new DomainEventEnvelope(
                UUID.randomUUID(), aggregateId, "Account", 2L, "MoneyDeposited", 99, recordedAt, metadata, payload
        );

        assertThatThrownBy(() -> registry.upcastToCanonical(futureEvent))
                .isInstanceOf(UnsupportedEventVersionException.class)
                .hasMessageContaining("Unsupported future event version: 99");
    }

    @Test
    @DisplayName("6. Unknown event type throws UnknownEventTypeException")
    void testUnknownEventType() {
        ObjectNode payload = objectMapper.createObjectNode().put("someField", "val");
        DomainEventEnvelope unknownEvent = new DomainEventEnvelope(
                UUID.randomUUID(), aggregateId, "Account", 2L, "UnknownEvent", 1, recordedAt, metadata, payload
        );

        assertThatThrownBy(() -> registry.upcastToCanonical(unknownEvent))
                .isInstanceOf(UnknownEventTypeException.class)
                .hasMessageContaining("Unknown event type: 'UnknownEvent'");
    }

    @Test
    @DisplayName("7. Version chain: chaining v1 -> v2 -> v3 executes sequentially")
    void testVersionChaining() {
        // Create custom EventSchemaRegistry that defines MoneyDeposited currentVersion as 3
        EventSchemaRegistry customSchema = new EventSchemaRegistry() {
            @Override
            public int getCurrentVersion(String eventType) {
                if ("MoneyDeposited".equals(eventType)) return 3;
                return super.getCurrentVersion(eventType);
            }

            @Override
            public boolean isSupportedVersion(String eventType, int version) {
                if ("MoneyDeposited".equals(eventType)) return Set.of(1, 2, 3).contains(version);
                return super.isSupportedVersion(eventType, version);
            }

            @Override
            public void validateVersion(String eventType, int version) {
                if ("MoneyDeposited".equals(eventType)) {
                    if (version < 1) throw new UnsupportedEventVersionException(eventType, version);
                    if (version > 3) throw new UnsupportedEventVersionException(eventType, version);
                    return;
                }
                super.validateVersion(eventType, version);
            }
        };

        // Custom v2 -> v3 upcaster adding "channel": "DEFAULT_CHANNEL"
        EventUpcaster v2ToV3Upcaster = new EventUpcaster() {
            @Override
            public String eventType() {
                return "MoneyDeposited";
            }

            @Override
            public int sourceVersion() {
                return 2;
            }

            @Override
            public int targetVersion() {
                return 3;
            }

            @Override
            public DomainEventEnvelope upcast(DomainEventEnvelope event) {
                ObjectNode payload = ((ObjectNode) event.payload()).deepCopy();
                payload.put("channel", "DEFAULT_CHANNEL");
                return new DomainEventEnvelope(
                    event.eventId(), event.aggregateId(), event.aggregateType(), event.sequenceNumber(),
                    event.eventType(), 3, event.recordedAt(), event.metadata(), payload
                );
            }
        };

        EventUpcasterRegistry chainRegistry = new EventUpcasterRegistry(customSchema);
        chainRegistry.register(new MoneyDepositedV1ToV2Upcaster());
        chainRegistry.register(v2ToV3Upcaster);

        DomainEventEnvelope v1 = createRawV1Deposit(30000L);
        DomainEventEnvelope v3 = chainRegistry.upcastToCanonical(v1);

        assertThat(v3.eventVersion()).isEqualTo(3);
        assertThat(v3.payload().get("source").asText()).isEqualTo("MANUAL");
        assertThat(v3.payload().get("channel").asText()).isEqualTo("DEFAULT_CHANNEL");
        assertThat(v3.payload().get("amountMinor").asLong()).isEqualTo(30000L);
    }
}
