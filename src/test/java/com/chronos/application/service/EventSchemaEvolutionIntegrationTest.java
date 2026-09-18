package com.chronos.application.service;

import com.chronos.application.command.CommandContext;
import com.chronos.application.command.CommandResult;
import com.chronos.application.model.TemporalResult;
import com.chronos.application.port.EventStore;
import com.chronos.domain.account.AccountState;
import com.chronos.domain.account.AccountStatus;
import com.chronos.domain.account.command.CreateAccount;
import com.chronos.domain.account.command.DepositMoney;
import com.chronos.domain.account.command.WithdrawMoney;
import com.chronos.domain.event.DomainEventEnvelope;
import com.chronos.domain.event.upcasting.exception.MalformedEventPayloadException;
import com.chronos.domain.event.upcasting.exception.UnsupportedEventVersionException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
public class EventSchemaEvolutionIntegrationTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        com.chronos.TestDatabaseHelper.configureProperties(registry);
    }

    @Autowired
    private EventStore eventStore;

    @Autowired
    private AccountCommandProcessor commandProcessor;

    @Autowired
    private TemporalStateReconstructor temporalReconstructor;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("TRUNCATE TABLE snapshots");
        jdbcTemplate.update("TRUNCATE TABLE outbox_events");
        jdbcTemplate.update("TRUNCATE TABLE inbox_events");
        jdbcTemplate.update("TRUNCATE TABLE event_store");
    }

    @Test
    @DisplayName("GOLDEN REPLAY & IMMUTABILITY: Real PostgreSQL legacy v1 event replays correctly without mutating raw database row")
    void testGoldenLegacyReplayAndRawImmutability() throws Exception {
        UUID accountId = UUID.randomUUID();
        UUID e1Id = UUID.randomUUID();
        UUID e2Id = UUID.randomUUID();
        Instant t1 = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
        Instant t2 = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);

        // 1. Seed sequence 1: AccountCreated (v1)
        String sql = """
            INSERT INTO event_store (
                event_id, aggregate_id, aggregate_type, sequence_number,
                event_type, event_version, recorded_at, metadata, payload
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
            """;

        String createPayload = """
            {"currency": "USD", "initialOverdraftLimitMinor": 50000, "initialTransactionLimitMinor": 200000}
            """;
        String metaJson = """
            {"correlationId": "%s", "actor": "system-admin"}
            """.formatted(UUID.randomUUID());

        jdbcTemplate.update(sql, e1Id, accountId, "Account", 1L, "AccountCreated", 1, Timestamp.from(t1), metaJson, createPayload);

        // 2. Seed sequence 2: Genuine legacy v1 MoneyDeposited (notice: NO "source" field in payload)
        String v1DepositPayload = """
            {"amountMinor": 150000, "currency": "USD", "resultingBalanceMinor": 150000}
            """;
        jdbcTemplate.update(sql, e2Id, accountId, "Account", 2L, "MoneyDeposited", 1, Timestamp.from(t2), metaJson, v1DepositPayload);

        // Capture raw database row state before replay
        Map<String, Object> rawRowBeforeReplay = jdbcTemplate.queryForMap(
            "SELECT event_id, event_version, payload::text as payload_text FROM event_store WHERE event_id = ?", e2Id
        );
        assertThat(rawRowBeforeReplay.get("event_version")).isEqualTo(1);
        String payloadBefore = (String) rawRowBeforeReplay.get("payload_text");
        assertThat(payloadBefore).doesNotContain("\"source\"");

        // 3. Execute canonical reconstruction through real TemporalStateReconstructor
        TemporalResult currentResult = temporalReconstructor.reconstructCurrentState(accountId);
        AccountState currentState = currentResult.reconstructedState();

        assertThat(currentState.accountId()).isEqualTo(accountId);
        assertThat(currentState.balanceMinor()).isEqualTo(150000L);
        assertThat(currentState.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(currentState.sequenceNumber()).isEqualTo(2L);

        // 4. Verify Raw PostgreSQL row remains strictly IMMUTABLE
        Map<String, Object> rawRowAfterReplay = jdbcTemplate.queryForMap(
            "SELECT event_id, event_version, payload::text as payload_text FROM event_store WHERE event_id = ?", e2Id
        );
        assertThat(rawRowAfterReplay.get("event_version")).isEqualTo(1);
        String payloadAfter = (String) rawRowAfterReplay.get("payload_text");
        assertThat(payloadAfter).isEqualTo(payloadBefore);
        assertThat(payloadAfter).doesNotContain("\"source\"");

        // 5. Verify EventStore.loadStream returns authoritative historical row preserving v1
        List<DomainEventEnvelope> rawStream = eventStore.loadStream(accountId);
        assertThat(rawStream).hasSize(2);
        DomainEventEnvelope rawDeposit = rawStream.get(1);
        assertThat(rawDeposit.eventId()).isEqualTo(e2Id);
        assertThat(rawDeposit.eventVersion()).isEqualTo(1);
        assertThat(rawDeposit.payload().has("source")).isFalse();

        // 6. Verify historical time-travel stateAt(T) across legacy event
        TemporalResult atT1 = temporalReconstructor.reconstructStateAt(accountId, t1.plusSeconds(30));
        assertThat(atT1.reconstructedState().balanceMinor()).isEqualTo(0L);
        assertThat(atT1.reconstructedState().sequenceNumber()).isEqualTo(1L);

        TemporalResult atT2 = temporalReconstructor.reconstructStateAt(accountId, t2.plusSeconds(30));
        assertThat(atT2.reconstructedState().balanceMinor()).isEqualTo(150000L);
        assertThat(atT2.reconstructedState().sequenceNumber()).isEqualTo(2L);
    }

    @Test
    @DisplayName("MIXED-VERSION STREAM: Stream containing v1 and v2 events correctly reconstructs state")
    void testMixedVersionStreamReplay() {
        UUID accountId = UUID.randomUUID();
        CommandContext ctx = CommandContext.of("mixed-stream-user");

        Instant t0 = Instant.now().minusSeconds(120).truncatedTo(ChronoUnit.MILLIS);
        Instant t1 = t0.plusSeconds(10);

        String sql = """
            INSERT INTO event_store (
                event_id, aggregate_id, aggregate_type, sequence_number,
                event_type, event_version, recorded_at, metadata, payload
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
            """;

        // Sequence 1: AccountCreated (v1) in the past
        UUID createId = UUID.randomUUID();
        String createPayload = """
            {"currency": "INR", "initialOverdraftLimitMinor": 10000, "initialTransactionLimitMinor": 500000}
            """;
        String metaJson = """
            {"correlationId": "%s", "actor": "mixed-stream-user"}
            """.formatted(UUID.randomUUID());
        jdbcTemplate.update(sql, createId, accountId, "Account", 1L, "AccountCreated", 1, Timestamp.from(t0), metaJson, createPayload);

        // Sequence 2: Legacy v1 MoneyDeposited (100000) in the past
        UUID legacyDepositId = UUID.randomUUID();
        String legacyPayload = """
            {"amountMinor": 100000, "currency": "INR", "resultingBalanceMinor": 100000}
            """;
        jdbcTemplate.update(sql, legacyDepositId, accountId, "Account", 2L, "MoneyDeposited", 1, Timestamp.from(t1), metaJson, legacyPayload);

        // Sequence 3: Modern v2 MoneyDeposited (50000) processed through AccountCommandProcessor (recordedAt = Instant.now() > t1)
        CommandResult v2DepositResult = commandProcessor.process(new DepositMoney(accountId, 50000L, "ONLINE_TRANSFER"), ctx);
        assertThat(v2DepositResult.resultingState().balanceMinor()).isEqualTo(150000L);
        assertThat(v2DepositResult.resultingState().sequenceNumber()).isEqualTo(3L);

        // Sequence 4: MoneyWithdrawn (30000) processed through AccountCommandProcessor
        CommandResult withdrawalResult = commandProcessor.process(new WithdrawMoney(accountId, 30000L), ctx);
        assertThat(withdrawalResult.resultingState().balanceMinor()).isEqualTo(120000L);
        assertThat(withdrawalResult.resultingState().sequenceNumber()).isEqualTo(4L);

        // Reconstruct from scratch using full event replay
        TemporalResult fullReplay = temporalReconstructor.reconstructFullReplayCurrentState(accountId);
        assertThat(fullReplay.reconstructedState().balanceMinor()).isEqualTo(120000L);
        assertThat(fullReplay.reconstructedState().sequenceNumber()).isEqualTo(4L);

        // Verify versions stored in event_store
        List<DomainEventEnvelope> stream = eventStore.loadStream(accountId);
        assertThat(stream).hasSize(4);
        assertThat(stream.get(0).eventVersion()).isEqualTo(1); // AccountCreated v1
        assertThat(stream.get(1).eventVersion()).isEqualTo(1); // MoneyDeposited legacy v1
        assertThat(stream.get(2).eventVersion()).isEqualTo(2); // MoneyDeposited modern v2
        assertThat(stream.get(3).eventVersion()).isEqualTo(1); // MoneyWithdrawn v1

        // Verify outbox record for sequence 3 has eventVersion = 2
        Integer outboxVersion = jdbcTemplate.queryForObject(
            "SELECT event_version FROM outbox_events WHERE aggregate_id = ? AND sequence_number = 3",
            Integer.class, accountId
        );
        assertThat(outboxVersion).isEqualTo(2);
    }

    @Test
    @DisplayName("NEW WRITES: Newly generated deposit events write eventVersion = 2 to both EventStore and Outbox")
    void testNewWritesUseCanonicalVersion2() throws Exception {
        UUID accountId = UUID.randomUUID();
        CommandContext ctx = CommandContext.of("writer-user");

        commandProcessor.process(new CreateAccount(accountId, "EUR", 0L, 100000L), ctx);
        CommandResult res = commandProcessor.process(new DepositMoney(accountId, 80000L, "SEPA_TRANSFER"), ctx);

        assertThat(res.emittedEvents()).hasSize(1);
        DomainEventEnvelope emitted = res.emittedEvents().get(0);
        assertThat(emitted.eventType()).isEqualTo("MoneyDeposited");
        assertThat(emitted.eventVersion()).isEqualTo(2);
        assertThat(emitted.payload().get("source").asText()).isEqualTo("SEPA_TRANSFER");

        // Check Event Store table
        Map<String, Object> eventStoreRow = jdbcTemplate.queryForMap(
            "SELECT event_version, payload::text as payload_text FROM event_store WHERE aggregate_id = ? AND sequence_number = 2",
            accountId
        );
        assertThat(eventStoreRow.get("event_version")).isEqualTo(2);
        JsonNode storePayload = objectMapper.readTree((String) eventStoreRow.get("payload_text"));
        assertThat(storePayload.get("source").asText()).isEqualTo("SEPA_TRANSFER");

        // Check Transactional Outbox table
        Map<String, Object> outboxRow = jdbcTemplate.queryForMap(
            "SELECT event_version, event_envelope::text as envelope_text FROM outbox_events WHERE aggregate_id = ? AND sequence_number = 2",
            accountId
        );
        assertThat(outboxRow.get("event_version")).isEqualTo(2);
        JsonNode outboxEnvelope = objectMapper.readTree((String) outboxRow.get("envelope_text"));
        assertThat(outboxEnvelope.get("eventVersion").asInt()).isEqualTo(2);
        assertThat(outboxEnvelope.get("payload").get("source").asText()).isEqualTo("SEPA_TRANSFER");
    }

    @Test
    @DisplayName("FAILURE HANDLING: Future version (v99) in event_store throws UnsupportedEventVersionException during replay")
    void testUnsupportedFutureVersionInEventStoreThrows() {
        UUID accountId = UUID.randomUUID();
        Instant now = Instant.now();

        String sql = """
            INSERT INTO event_store (
                event_id, aggregate_id, aggregate_type, sequence_number,
                event_type, event_version, recorded_at, metadata, payload
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
            """;
        jdbcTemplate.update(sql, UUID.randomUUID(), accountId, "Account", 1L, "AccountCreated", 1, Timestamp.from(now), "{}", "{\"currency\":\"USD\"}");
        jdbcTemplate.update(sql, UUID.randomUUID(), accountId, "Account", 2L, "MoneyDeposited", 99, Timestamp.from(now.plusSeconds(1)), "{}", "{\"amountMinor\": 500}");

        assertThatThrownBy(() -> temporalReconstructor.reconstructCurrentState(accountId))
            .isInstanceOf(UnsupportedEventVersionException.class)
            .hasMessageContaining("Unsupported future event version: 99");
    }

    @Test
    @DisplayName("FAILURE HANDLING: Malformed legacy payload in event_store throws MalformedEventPayloadException")
    void testMalformedLegacyPayloadInEventStoreThrows() {
        UUID accountId = UUID.randomUUID();
        Instant now = Instant.now();

        String sql = """
            INSERT INTO event_store (
                event_id, aggregate_id, aggregate_type, sequence_number,
                event_type, event_version, recorded_at, metadata, payload
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
            """;
        jdbcTemplate.update(sql, UUID.randomUUID(), accountId, "Account", 1L, "AccountCreated", 1, Timestamp.from(now), "{}", "{\"currency\":\"USD\"}");
        // Missing amountMinor in legacy payload
        jdbcTemplate.update(sql, UUID.randomUUID(), accountId, "Account", 2L, "MoneyDeposited", 1, Timestamp.from(now.plusSeconds(1)), "{}", "{\"currency\":\"USD\"}");

        assertThatThrownBy(() -> temporalReconstructor.reconstructCurrentState(accountId))
            .isInstanceOf(MalformedEventPayloadException.class)
            .hasMessageContaining("Missing or non-positive amountMinor");
    }
}
