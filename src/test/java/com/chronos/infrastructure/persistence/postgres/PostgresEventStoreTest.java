package com.chronos.infrastructure.persistence.postgres;

import com.chronos.application.port.EventStore;
import com.chronos.application.port.OptimisticConcurrencyException;
import com.chronos.domain.event.DomainEventEnvelope;
import com.chronos.domain.event.EventMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class PostgresEventStoreTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5432/chronos_test_db");
        registry.add("spring.datasource.username", () -> "test_user");
        registry.add("spring.datasource.password", () -> "test_password");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
    }

    @Autowired
    private EventStore eventStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        // Clean event_store table between tests for isolation
        jdbcTemplate.update("TRUNCATE TABLE event_store");
    }

    @Test
    @DisplayName("TEST 1 — append one event: verifies attributes and payload are stored correctly")
    void test1_appendOneEvent() {
        UUID aggregateId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        UUID causationId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        EventMetadata metadata = new EventMetadata(correlationId, causationId, "user-123", "idemp-001");
        ObjectNode payload = objectMapper.createObjectNode()
                .put("currency", "INR")
                .put("initialOverdraftLimitMinor", 500000L)
                .put("initialTransactionLimitMinor", 1000000L);

        DomainEventEnvelope envelope = new DomainEventEnvelope(
                eventId, aggregateId, "Account", 1L, "AccountCreated", 1, now, metadata, payload
        );

        eventStore.append(aggregateId, 0L, List.of(envelope));

        List<DomainEventEnvelope> stream = eventStore.loadStream(aggregateId);
        assertThat(stream).hasSize(1);

        DomainEventEnvelope stored = stream.get(0);
        assertThat(stored.eventId()).isEqualTo(eventId);
        assertThat(stored.aggregateId()).isEqualTo(aggregateId);
        assertThat(stored.aggregateType()).isEqualTo("Account");
        assertThat(stored.sequenceNumber()).isEqualTo(1L);
        assertThat(stored.eventType()).isEqualTo("AccountCreated");
        assertThat(stored.eventVersion()).isEqualTo(1);
        assertThat(stored.recordedAt()).isEqualTo(now);
        assertThat(stored.metadata().correlationId()).isEqualTo(correlationId);
        assertThat(stored.metadata().causationId()).isEqualTo(causationId);
        assertThat(stored.metadata().actorId()).isEqualTo("user-123");
        assertThat(stored.metadata().idempotencyKey()).isEqualTo("idemp-001");
        assertThat(stored.payload().get("currency").asText()).isEqualTo("INR");
        assertThat(stored.payload().get("initialOverdraftLimitMinor").asLong()).isEqualTo(500000L);
    }

    @Test
    @DisplayName("TEST 2 — load stream: verifies ordering, types, and sequence numbers")
    void test2_loadStream() {
        UUID aggregateId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        EventMetadata metadata = new EventMetadata(UUID.randomUUID(), UUID.randomUUID(), "actor", null);

        DomainEventEnvelope e1 = new DomainEventEnvelope(
                UUID.randomUUID(), aggregateId, "Account", 1L, "AccountCreated", 1, now, metadata, objectMapper.createObjectNode()
        );
        DomainEventEnvelope e2 = new DomainEventEnvelope(
                UUID.randomUUID(), aggregateId, "Account", 2L, "MoneyDeposited", 1, now.plusSeconds(1), metadata, objectMapper.createObjectNode().put("amountMinor", 100000L)
        );
        DomainEventEnvelope e3 = new DomainEventEnvelope(
                UUID.randomUUID(), aggregateId, "Account", 3L, "MoneyWithdrawn", 1, now.plusSeconds(2), metadata, objectMapper.createObjectNode().put("amountMinor", 30000L)
        );

        eventStore.append(aggregateId, 0L, List.of(e1, e2, e3));

        List<DomainEventEnvelope> stream = eventStore.loadStream(aggregateId);
        assertThat(stream).hasSize(3);
        assertThat(stream).extracting(DomainEventEnvelope::eventType)
                .containsExactly("AccountCreated", "MoneyDeposited", "MoneyWithdrawn");
        assertThat(stream).extracting(DomainEventEnvelope::sequenceNumber)
                .containsExactly(1L, 2L, 3L);
    }

    @Test
    @DisplayName("TEST 3 — current version: verifies correct version returned after appends")
    void test3_currentVersion() {
        UUID aggregateId = UUID.randomUUID();
        assertThat(eventStore.currentVersion(aggregateId)).isEqualTo(0L);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        EventMetadata metadata = new EventMetadata(UUID.randomUUID(), UUID.randomUUID(), "actor", null);

        DomainEventEnvelope e1 = new DomainEventEnvelope(
                UUID.randomUUID(), aggregateId, "Account", 1L, "AccountCreated", 1, now, metadata, objectMapper.createObjectNode()
        );
        DomainEventEnvelope e2 = new DomainEventEnvelope(
                UUID.randomUUID(), aggregateId, "Account", 2L, "MoneyDeposited", 1, now.plusSeconds(1), metadata, objectMapper.createObjectNode()
        );
        DomainEventEnvelope e3 = new DomainEventEnvelope(
                UUID.randomUUID(), aggregateId, "Account", 3L, "MoneyWithdrawn", 1, now.plusSeconds(2), metadata, objectMapper.createObjectNode()
        );

        eventStore.append(aggregateId, 0L, List.of(e1, e2, e3));
        assertThat(eventStore.currentVersion(aggregateId)).isEqualTo(3L);
    }

    @Test
    @DisplayName("TEST 4 — timestamp query: verifies inclusive recordedAt query boundary and ordering")
    void test4_timestampQuery() {
        UUID aggregateId = UUID.randomUUID();
        Instant baseTime = Instant.parse("2026-09-15T12:00:00Z");
        EventMetadata metadata = new EventMetadata(UUID.randomUUID(), UUID.randomUUID(), "actor", null);

        DomainEventEnvelope e1 = new DomainEventEnvelope(
                UUID.randomUUID(), aggregateId, "Account", 1L, "AccountCreated", 1, baseTime, metadata, objectMapper.createObjectNode()
        );
        DomainEventEnvelope e2 = new DomainEventEnvelope(
                UUID.randomUUID(), aggregateId, "Account", 2L, "MoneyDeposited", 1, baseTime.plusSeconds(60), metadata, objectMapper.createObjectNode()
        );
        DomainEventEnvelope e3 = new DomainEventEnvelope(
                UUID.randomUUID(), aggregateId, "Account", 3L, "MoneyWithdrawn", 1, baseTime.plusSeconds(120), metadata, objectMapper.createObjectNode()
        );

        eventStore.append(aggregateId, 0L, List.of(e1, e2, e3));

        // Query exactly at baseTime + 60s (should include e1 and e2)
        List<DomainEventEnvelope> upTo60 = eventStore.loadStreamUpTo(aggregateId, baseTime.plusSeconds(60));
        assertThat(upTo60).hasSize(2);
        assertThat(upTo60).extracting(DomainEventEnvelope::sequenceNumber).containsExactly(1L, 2L);

        // Query before e1 (should return empty list)
        List<DomainEventEnvelope> beforeE1 = eventStore.loadStreamUpTo(aggregateId, baseTime.minusSeconds(10));
        assertThat(beforeE1).isEmpty();
    }

    @Test
    @DisplayName("TEST 5 — metadata/payload round trip: verifies complex JSON metadata and payload round-trip")
    void test5_metadataPayloadRoundTrip() {
        UUID aggregateId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        EventMetadata metadata = new EventMetadata(UUID.randomUUID(), UUID.randomUUID(), "admin-user", "key-999-abc");
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("targetEventId", UUID.randomUUID().toString());
        payload.put("correctionType", "REVERSAL");
        payload.put("direction", "CREDIT");
        payload.put("adjustmentAmountMinor", 45000L);
        payload.put("reason", "Special audit reversal for system error #8821");

        DomainEventEnvelope event = new DomainEventEnvelope(
                UUID.randomUUID(), aggregateId, "Account", 1L, "CorrectionIssued", 1, now, metadata, payload
        );

        eventStore.append(aggregateId, 0L, List.of(event));

        List<DomainEventEnvelope> stream = eventStore.loadStream(aggregateId);
        assertThat(stream).hasSize(1);
        DomainEventEnvelope read = stream.get(0);

        assertThat(read.metadata()).isEqualTo(metadata);
        assertThat(read.payload().get("correctionType").asText()).isEqualTo("REVERSAL");
        assertThat(read.payload().get("direction").asText()).isEqualTo("CREDIT");
        assertThat(read.payload().get("adjustmentAmountMinor").asLong()).isEqualTo(45000L);
        assertThat(read.payload().get("reason").asText()).isEqualTo("Special audit reversal for system error #8821");
    }

    @Test
    @DisplayName("TEST 6 — OCC conflict: verifies OptimisticConcurrencyException on stale expected version")
    void test6_occConflict() {
        UUID aggregateId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        EventMetadata metadata = new EventMetadata(UUID.randomUUID(), UUID.randomUUID(), "actor", null);

        DomainEventEnvelope e1 = new DomainEventEnvelope(
                UUID.randomUUID(), aggregateId, "Account", 1L, "AccountCreated", 1, now, metadata, objectMapper.createObjectNode()
        );

        // First append with expected version 0 succeeds
        eventStore.append(aggregateId, 0L, List.of(e1));
        assertThat(eventStore.currentVersion(aggregateId)).isEqualTo(1L);

        // Second append with stale expected version 0 must fail with OptimisticConcurrencyException
        DomainEventEnvelope e2Stale = new DomainEventEnvelope(
                UUID.randomUUID(), aggregateId, "Account", 1L, "MoneyDeposited", 1, now.plusSeconds(1), metadata, objectMapper.createObjectNode()
        );

        assertThatThrownBy(() -> eventStore.append(aggregateId, 0L, List.of(e2Stale)))
                .isInstanceOf(OptimisticConcurrencyException.class)
                .hasMessageContaining("Concurrency conflict for aggregate");

        // Verify version remained 1
        assertThat(eventStore.currentVersion(aggregateId)).isEqualTo(1L);
    }

    @Test
    @DisplayName("TEST 7 — atomic batch append: verifies batch commits atomically or rolls back on error")
    void test7_atomicBatchAppend() {
        UUID aggregateId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        EventMetadata metadata = new EventMetadata(UUID.randomUUID(), UUID.randomUUID(), "actor", null);

        DomainEventEnvelope e1 = new DomainEventEnvelope(
                UUID.randomUUID(), aggregateId, "Account", 1L, "AccountCreated", 1, now, metadata, objectMapper.createObjectNode()
        );
        DomainEventEnvelope e2 = new DomainEventEnvelope(
                UUID.randomUUID(), aggregateId, "Account", 2L, "MoneyDeposited", 1, now.plusSeconds(1), metadata, objectMapper.createObjectNode()
        );

        // Valid batch append commits all events
        eventStore.append(aggregateId, 0L, List.of(e1, e2));
        assertThat(eventStore.loadStream(aggregateId)).hasSize(2);

        // Invalid batch with sequence gap (expecting sequence 3, but event has sequence 5) fails atomically
        DomainEventEnvelope e3InvalidSequence = new DomainEventEnvelope(
                UUID.randomUUID(), aggregateId, "Account", 5L, "MoneyWithdrawn", 1, now.plusSeconds(2), metadata, objectMapper.createObjectNode()
        );

        assertThatThrownBy(() -> eventStore.append(aggregateId, 2L, List.of(e3InvalidSequence)))
                .isInstanceOf(IllegalArgumentException.class);

        // Stream remains at size 2
        assertThat(eventStore.loadStream(aggregateId)).hasSize(2);
    }

    @Test
    @DisplayName("TEST 8 — empty stream: verifies non-existent aggregate returns empty list")
    void test8_emptyStream() {
        UUID nonExistent = UUID.randomUUID();
        List<DomainEventEnvelope> stream = eventStore.loadStream(nonExistent);
        assertThat(stream).isEmpty();
        assertThat(eventStore.currentVersion(nonExistent)).isEqualTo(0L);
    }

    @Test
    @DisplayName("TEST 9 — aggregate isolation: verifies events for Aggregate A do not leak into Aggregate B")
    void test9_aggregateIsolation() {
        UUID aggregateA = UUID.randomUUID();
        UUID aggregateB = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        EventMetadata metadata = new EventMetadata(UUID.randomUUID(), UUID.randomUUID(), "actor", null);

        DomainEventEnvelope eventA = new DomainEventEnvelope(
                UUID.randomUUID(), aggregateA, "Account", 1L, "AccountCreated", 1, now, metadata, objectMapper.createObjectNode()
        );
        DomainEventEnvelope eventB = new DomainEventEnvelope(
                UUID.randomUUID(), aggregateB, "Account", 1L, "AccountCreated", 1, now, metadata, objectMapper.createObjectNode()
        );

        eventStore.append(aggregateA, 0L, List.of(eventA));
        eventStore.append(aggregateB, 0L, List.of(eventB));

        List<DomainEventEnvelope> streamA = eventStore.loadStream(aggregateA);
        List<DomainEventEnvelope> streamB = eventStore.loadStream(aggregateB);

        assertThat(streamA).hasSize(1);
        assertThat(streamA.get(0).aggregateId()).isEqualTo(aggregateA);

        assertThat(streamB).hasSize(1);
        assertThat(streamB.get(0).aggregateId()).isEqualTo(aggregateB);
    }

    @Test
    @DisplayName("TEST 10 — sequence uniqueness: verifies database unique constraint protects aggregate sequence")
    void test10_sequenceUniqueness() {
        UUID aggregateId = UUID.randomUUID();
        UUID eventId1 = UUID.randomUUID();
        UUID eventId2 = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        EventMetadata metadata = new EventMetadata(UUID.randomUUID(), UUID.randomUUID(), "actor", null);

        DomainEventEnvelope e1 = new DomainEventEnvelope(
                eventId1, aggregateId, "Account", 1L, "AccountCreated", 1, now, metadata, objectMapper.createObjectNode()
        );

        eventStore.append(aggregateId, 0L, List.of(e1));

        // Manually attempt to bypass application version checks and force a direct SQL insert with duplicate sequence 1
        String sql = """
            INSERT INTO event_store (
                event_id, aggregate_id, aggregate_type, sequence_number,
                event_type, event_version, recorded_at, metadata, payload
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
            """;

        assertThatThrownBy(() -> jdbcTemplate.update(
                sql,
                eventId2, aggregateId, "Account", 1L, "MoneyDeposited", 1,
                java.sql.Timestamp.from(now), "{}", "{}"
        )).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }
}
