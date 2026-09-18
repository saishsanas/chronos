package com.chronos.application.service;

import com.chronos.application.model.TemporalResult;
import com.chronos.application.port.SnapshotRepository;
import com.chronos.domain.account.AccountState;
import com.chronos.domain.account.AccountStatus;
import com.chronos.domain.snapshot.Snapshot;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class SnapshotSchemaCompatibilityIntegrationTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        com.chronos.TestDatabaseHelper.configureProperties(registry);
    }

    @Autowired
    private TemporalStateReconstructor temporalReconstructor;

    @Autowired
    private SnapshotRepository snapshotRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("TRUNCATE TABLE snapshots");
        jdbcTemplate.update("TRUNCATE TABLE event_store");
    }

    @Test
    @DisplayName("A. Compatible snapshot + newer evolved events reconstructs cleanly")
    void testCompatibleSnapshotWithEvolvedEvents() {
        UUID accountId = UUID.randomUUID();
        Instant t0 = Instant.now().minus(10, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);

        // Seed sequence 1 (AccountCreated v1) and sequence 2 (legacy MoneyDeposited v1) in event_store
        String eventSql = """
            INSERT INTO event_store (
                event_id, aggregate_id, aggregate_type, sequence_number,
                event_type, event_version, recorded_at, metadata, payload
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
            """;
        jdbcTemplate.update(eventSql, UUID.randomUUID(), accountId, "Account", 1L, "AccountCreated", 1, Timestamp.from(t0), "{}", "{\"currency\":\"USD\"}");
        jdbcTemplate.update(eventSql, UUID.randomUUID(), accountId, "Account", 2L, "MoneyDeposited", 1, Timestamp.from(t0.plusSeconds(5)), "{}", "{\"amountMinor\": 100000}");

        // Save compatible snapshot at sequence 2 (balance = 100000)
        AccountState snapshotState = new AccountState(
            accountId, "USD", 100000L, 0L, 500000L, AccountStatus.ACTIVE, 2L, t0.plusSeconds(5)
        );
        Snapshot snapshot = Snapshot.create(snapshotState);
        snapshotRepository.save(snapshot);

        // Seed sequence 3: MoneyDeposited v2 with "source": "CARD"
        jdbcTemplate.update(eventSql, UUID.randomUUID(), accountId, "Account", 3L, "MoneyDeposited", 2, Timestamp.from(t0.plusSeconds(10)), "{}", "{\"amountMinor\": 50000, \"source\": \"CARD\"}");

        // Reconstruct current state - should use snapshot at sequence 2 and replay sequence 3 (v2)
        TemporalResult result = temporalReconstructor.reconstructCurrentState(accountId);

        assertThat(result.snapshotUsed()).isTrue();
        assertThat(result.snapshotSequenceNumber()).isEqualTo(2L);
        assertThat(result.eventsReplayedCount()).isEqualTo(1); // Only seq 3 replayed
        assertThat(result.reconstructedState().balanceMinor()).isEqualTo(150000L);
        assertThat(result.sequenceNumber()).isEqualTo(3L);
    }

    @Test
    @DisplayName("B & C. Incompatible snapshot metadata causes rejection and clean fallback to full event replay with upcasting")
    void testIncompatibleSnapshotRejectedWithFullReplayFallback() {
        UUID accountId = UUID.randomUUID();
        Instant t0 = Instant.now().minus(10, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);

        // Seed sequence 1 (AccountCreated v1), sequence 2 (legacy MoneyDeposited v1), and sequence 3 (MoneyDeposited v2)
        String eventSql = """
            INSERT INTO event_store (
                event_id, aggregate_id, aggregate_type, sequence_number,
                event_type, event_version, recorded_at, metadata, payload
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
            """;
        jdbcTemplate.update(eventSql, UUID.randomUUID(), accountId, "Account", 1L, "AccountCreated", 1, Timestamp.from(t0), "{}", "{\"currency\":\"EUR\"}");
        jdbcTemplate.update(eventSql, UUID.randomUUID(), accountId, "Account", 2L, "MoneyDeposited", 1, Timestamp.from(t0.plusSeconds(5)), "{}", "{\"amountMinor\": 200000}");
        jdbcTemplate.update(eventSql, UUID.randomUUID(), accountId, "Account", 3L, "MoneyDeposited", 2, Timestamp.from(t0.plusSeconds(10)), "{}", "{\"amountMinor\": 80000, \"source\": \"WIRE\"}");

        // Save INCOMPATIBLE snapshot at sequence 2 with stale replayLogicHash
        AccountState corruptSnapshotState = new AccountState(
            accountId, "EUR", 999999L, 0L, 500000L, AccountStatus.ACTIVE, 2L, t0.plusSeconds(5)
        );
        Snapshot incompatibleSnapshot = new Snapshot(
            UUID.randomUUID(), accountId, 2L, Snapshot.CURRENT_SNAPSHOT_VERSION,
            Snapshot.CURRENT_DOMAIN_VERSION, "OUTDATED_REPLAY_LOGIC_HASH", Instant.now(), corruptSnapshotState
        );
        snapshotRepository.save(incompatibleSnapshot);

        // Verify snapshot is invalid
        assertThat(incompatibleSnapshot.isValid()).isFalse();

        // Reconstruct current state - must reject snapshot and fall back to full event replay
        TemporalResult result = temporalReconstructor.reconstructCurrentState(accountId);

        assertThat(result.snapshotUsed()).isFalse(); // Incompatible snapshot rejected!
        assertThat(result.snapshotSequenceNumber()).isEqualTo(0L);
        assertThat(result.eventsReplayedCount()).isEqualTo(3); // All 3 events replayed
        assertThat(result.reconstructedState().balanceMinor()).isEqualTo(280000L); // 200000 (v1 upcast) + 80000 (v2)
        assertThat(result.sequenceNumber()).isEqualTo(3L);
    }
}
