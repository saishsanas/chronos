package com.chronos.infrastructure.persistence.postgres;

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

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PostgresSnapshotRepositoryTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        com.chronos.TestDatabaseHelper.configureProperties(registry);
    }

    @Autowired
    private PostgresSnapshotRepository snapshotRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("TRUNCATE TABLE snapshots");
        jdbcTemplate.update("TRUNCATE TABLE event_store");
    }

    @Test
    @DisplayName("1-6. Save snapshot, load latest, load by sequence/timestamp, round-trip JSON & metadata, duplicate idempotency")
    void testSnapshotPersistenceAndRoundTrip() {
        UUID accountId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-17T15:00:00Z");

        AccountState state100 = new AccountState(
                accountId, "INR", 500000L, 100000L, 200000L, AccountStatus.ACTIVE, 100L, now
        );
        Snapshot snapshot100 = new Snapshot(
                UUID.randomUUID(), accountId, 100L, 1, 1, Snapshot.CURRENT_REPLAY_LOGIC_HASH, now, state100
        );

        // 1. Save
        snapshotRepository.save(snapshot100);

        // 2. Load latest
        Optional<Snapshot> loadedOpt = snapshotRepository.findLatestForAggregate(accountId);
        assertThat(loadedOpt).isPresent();
        Snapshot loaded = loadedOpt.get();

        assertThat(loaded.snapshotId()).isEqualTo(snapshot100.snapshotId());
        assertThat(loaded.aggregateId()).isEqualTo(accountId);
        assertThat(loaded.sequenceNumber()).isEqualTo(100L);
        assertThat(loaded.snapshotVersion()).isEqualTo(1);
        assertThat(loaded.domainVersion()).isEqualTo(1);
        assertThat(loaded.replayLogicHash()).isEqualTo(Snapshot.CURRENT_REPLAY_LOGIC_HASH);
        assertThat(loaded.state()).isEqualTo(state100);

        // 3. Duplicate save gracefully ignored (unique constraint aggregate_id + sequence + version)
        Snapshot duplicate = new Snapshot(
                UUID.randomUUID(), accountId, 100L, 1, 1, Snapshot.CURRENT_REPLAY_LOGIC_HASH, now.plusSeconds(5), state100
        );
        snapshotRepository.save(duplicate);
        Optional<Snapshot> loadedAfterDup = snapshotRepository.findLatestForAggregate(accountId);
        assertThat(loadedAfterDup.get().snapshotId()).isEqualTo(snapshot100.snapshotId());

        // 4. Save second snapshot at sequence 200
        Instant now200 = now.plusSeconds(3600);
        AccountState state200 = new AccountState(
                accountId, "INR", 800000L, 100000L, 200000L, AccountStatus.ACTIVE, 200L, now200
        );
        Snapshot snapshot200 = new Snapshot(
                UUID.randomUUID(), accountId, 200L, 1, 1, Snapshot.CURRENT_REPLAY_LOGIC_HASH, now200, state200
        );
        snapshotRepository.save(snapshot200);

        // 5. Verify latest returns sequence 200
        Optional<Snapshot> latestOpt = snapshotRepository.findLatestForAggregate(accountId);
        assertThat(latestOpt).isPresent();
        assertThat(latestOpt.get().sequenceNumber()).isEqualTo(200L);

        // 6. Query at or before sequence 150 returns sequence 100
        Optional<Snapshot> seq150Opt = snapshotRepository.findLatestAtOrBeforeSequence(accountId, 150L);
        assertThat(seq150Opt).isPresent();
        assertThat(seq150Opt.get().sequenceNumber()).isEqualTo(100L);
    }
}
