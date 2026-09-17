package com.chronos.application.service;

import com.chronos.application.command.CommandContext;
import com.chronos.application.command.CommandResult;
import com.chronos.application.model.TemporalResult;
import com.chronos.application.port.EventStore;
import com.chronos.application.port.SnapshotRepository;
import com.chronos.domain.account.AccountState;
import com.chronos.domain.account.AccountStatus;
import com.chronos.domain.account.command.CreateAccount;
import com.chronos.domain.account.command.DepositMoney;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class TemporalStateReconstructorTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        com.chronos.TestDatabaseHelper.configureProperties(registry);
        registry.add("chronos.snapshot.interval", () -> "100");
    }

    @Autowired
    private AccountCommandProcessor commandProcessor;

    @Autowired
    private TemporalStateReconstructor temporalReconstructor;

    @Autowired
    private EventStore eventStore;

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
    @DisplayName("7-10. Snapshot validity checks (version, domainVersion, replayLogicHash)")
    void testSnapshotValidityChecks() {
        UUID accountId = UUID.randomUUID();
        CommandContext ctx = CommandContext.of("admin");
        commandProcessor.process(new CreateAccount(accountId, "INR", 10000L, 50000L), ctx);
        commandProcessor.process(new DepositMoney(accountId, 1000L), ctx);

        AccountState state2 = commandProcessor.process(new DepositMoney(accountId, 2000L), ctx).resultingState();

        // Save snapshot with wrong replay logic hash
        Snapshot invalidSnapshot = new Snapshot(
                UUID.randomUUID(), accountId, 2L, 1, 1, "WRONG_REPLAY_HASH", Instant.now(), state2
        );
        snapshotRepository.save(invalidSnapshot);

        // Reconstructor should reject invalid snapshot and fall back to full replay
        TemporalResult result = temporalReconstructor.reconstructCurrentState(accountId);
        assertThat(result.snapshotUsed()).isFalse();
        assertThat(result.reconstructedState().balanceMinor()).isEqualTo(3000L);
    }

    @Test
    @DisplayName("11-15. Full replay vs snapshot-assisted replay equivalence and snapshot selection")
    void testFullReplayVsSnapshotEquivalence() {
        UUID accountId = UUID.randomUUID();
        CommandContext ctx = CommandContext.of("admin");
        commandProcessor.process(new CreateAccount(accountId, "INR", 10000L, 50000L), ctx);

        // Process 9 deposits so total sequence reaches 10
        for (int i = 0; i < 9; i++) {
            commandProcessor.process(new DepositMoney(accountId, 100L), ctx);
        }

        // Save exact snapshot at sequence 10
        AccountState stateAt10 = temporalReconstructor.reconstructFullReplayCurrentState(accountId).reconstructedState();
        Snapshot snapshot10 = Snapshot.create(stateAt10);
        snapshotRepository.save(snapshot10);

        // Process 5 more deposits (sequences 11 to 15)
        for (int i = 0; i < 5; i++) {
            commandProcessor.process(new DepositMoney(accountId, 100L), ctx);
        }

        TemporalResult fullResult = temporalReconstructor.reconstructFullReplayCurrentState(accountId);
        TemporalResult snapshotResult = temporalReconstructor.reconstructCurrentState(accountId);

        assertThat(snapshotResult.snapshotUsed()).isTrue();
        assertThat(snapshotResult.snapshotSequenceNumber()).isEqualTo(10L);
        assertThat(snapshotResult.reconstructedState()).isEqualTo(fullResult.reconstructedState());
    }

    @Test
    @DisplayName("16-20. Historical reconstruction stateAt(T) inclusive boundary & snapshot matching")
    void testHistoricalReconstructionInclusiveBoundary() {
        UUID accountId = UUID.randomUUID();
        CommandContext ctx = CommandContext.of("admin");

        CommandResult res1 = commandProcessor.process(new CreateAccount(accountId, "INR", 10000L, 50000L), ctx);
        Instant t1 = res1.emittedEvents().get(0).recordedAt();

        CommandResult res2 = commandProcessor.process(new DepositMoney(accountId, 5000L), ctx); // seq 2
        Instant t2 = res2.emittedEvents().get(0).recordedAt();

        CommandResult res3 = commandProcessor.process(new DepositMoney(accountId, 2000L), ctx); // seq 3
        Instant t3 = res3.emittedEvents().get(0).recordedAt();

        // 1. stateAt(t1) includes seq 1 (create balance = 0)
        TemporalResult resT1 = temporalReconstructor.reconstructStateAt(accountId, t1);
        assertThat(resT1.reconstructedState().balanceMinor()).isEqualTo(0L);
        assertThat(resT1.reconstructedState().sequenceNumber()).isEqualTo(1L);

        // 2. stateAt(t2) includes seq 1 + 2 (balance = 5000)
        TemporalResult resT2 = temporalReconstructor.reconstructStateAt(accountId, t2);
        assertThat(resT2.reconstructedState().balanceMinor()).isEqualTo(5000L);
        assertThat(resT2.reconstructedState().sequenceNumber()).isEqualTo(2L);

        // 3. stateAt(t3) includes seq 1 + 2 + 3 (balance = 7000)
        TemporalResult resT3 = temporalReconstructor.reconstructStateAt(accountId, t3);
        assertThat(resT3.reconstructedState().balanceMinor()).isEqualTo(7000L);
        assertThat(resT3.reconstructedState().sequenceNumber()).isEqualTo(3L);

        // 4. stateAt(before t1) returns uninitialized
        TemporalResult resBefore = temporalReconstructor.reconstructStateAt(accountId, t1.minusSeconds(100));
        assertThat(resBefore.reconstructedState().status()).isEqualTo(AccountStatus.UNINITIALIZED);
        assertThat(resBefore.reconstructedState().sequenceNumber()).isEqualTo(0L);
    }

    @Test
    @DisplayName("21-22 & 26-27. Automatic snapshot creation on threshold and performance work reduction proof")
    void testAutomaticSnapshotCreationAndWorkReductionProof() {
        UUID accountId = UUID.randomUUID();
        CommandContext ctx = CommandContext.of("admin");

        // 1. Create account (seq 1)
        commandProcessor.process(new CreateAccount(accountId, "INR", 10000L, 50000L), ctx);

        // 2. Process 199 deposits so total sequence becomes 200
        for (int i = 0; i < 199; i++) {
            commandProcessor.process(new DepositMoney(accountId, 100L), ctx);
        }

        // 3. Verify snapshot was automatically created at sequence 100 and sequence 200
        assertThat(snapshotRepository.findLatestForAggregate(accountId)).isPresent();
        assertThat(snapshotRepository.findLatestForAggregate(accountId).get().sequenceNumber()).isEqualTo(200L);

        // 4. Process 50 additional deposits (total sequence = 250)
        for (int i = 0; i < 50; i++) {
            commandProcessor.process(new DepositMoney(accountId, 100L), ctx);
        }

        // 5. Query current state with full replay vs snapshot-assisted replay
        TemporalResult fullResult = temporalReconstructor.reconstructFullReplayCurrentState(accountId);
        TemporalResult snapshotResult = temporalReconstructor.reconstructCurrentState(accountId);

        // 6. Verify 100% state equivalence
        assertThat(snapshotResult.reconstructedState()).isEqualTo(fullResult.reconstructedState());
        assertThat(snapshotResult.reconstructedState().sequenceNumber()).isEqualTo(250L);
        assertThat(snapshotResult.reconstructedState().balanceMinor()).isEqualTo(24900L);

        // 7. Verify performance work reduction: Full replay replayed 250 events, snapshot replay replayed ONLY 50 events!
        assertThat(fullResult.eventsReplayedCount()).isEqualTo(250);
        assertThat(snapshotResult.snapshotUsed()).isTrue();
        assertThat(snapshotResult.snapshotSequenceNumber()).isEqualTo(200L);
        assertThat(snapshotResult.eventsReplayedCount()).isEqualTo(50);
    }

    @Test
    @DisplayName("Audit Fix: Snapshot represented state timestamp is used for stateAt(T) eligibility")
    void testSnapshotRepresentedStateTemporalFiltering() {
        UUID accountId = UUID.randomUUID();
        CommandContext ctx = CommandContext.of("admin");

        CommandResult res1 = commandProcessor.process(new CreateAccount(accountId, "INR", 10000L, 50000L), ctx);
        Instant t1 = res1.emittedEvents().get(0).recordedAt();

        CommandResult res2 = commandProcessor.process(new DepositMoney(accountId, 5000L), ctx);
        Instant t2 = res2.emittedEvents().get(0).recordedAt();

        // State at sequence 2 has lastUpdatedAt = t2
        AccountState state2 = res2.resultingState();

        // Create snapshot at a much LATER physical creation time (t2 + 1 hour)
        Snapshot snapshotDelayed = new Snapshot(
                UUID.randomUUID(), accountId, 2L, 1, 1, Snapshot.CURRENT_REPLAY_LOGIC_HASH, t2.plusSeconds(3600), state2
        );
        snapshotRepository.save(snapshotDelayed);

        // Querying stateAt(t2) must match snapshot represented state (t2 <= t2) even though createdAt > t2
        TemporalResult resultAtT2 = temporalReconstructor.reconstructStateAt(accountId, t2);
        assertThat(resultAtT2.snapshotUsed()).isTrue();
        assertThat(resultAtT2.snapshotSequenceNumber()).isEqualTo(2L);
        assertThat(resultAtT2.reconstructedState().balanceMinor()).isEqualTo(5000L);

        // Querying stateAt(t1) must NOT match snapshot 2 because state2.lastUpdatedAt (t2) > t1
        TemporalResult resultAtT1 = temporalReconstructor.reconstructStateAt(accountId, t1);
        assertThat(resultAtT1.snapshotSequenceNumber()).isNotEqualTo(2L);
        assertThat(resultAtT1.reconstructedState().balanceMinor()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Audit Fix: Snapshot persistence failure does not invalidate command result or event store")
    void testSnapshotSaveFailureDoesNotInvalidateCommandResult() {
        UUID accountId = UUID.randomUUID();
        CommandContext ctx = CommandContext.of("admin");

        // Custom processor with a snapshot repository that throws an exception
        SnapshotRepository failingRepo = new SnapshotRepository() {
            @Override
            public void save(Snapshot snapshot) {
                throw new RuntimeException("Database disk full");
            }
            @Override
            public java.util.Optional<Snapshot> findLatestForAggregate(UUID aggregateId) { return java.util.Optional.empty(); }
            @Override
            public java.util.Optional<Snapshot> findLatestAtOrBeforeSequence(UUID aggregateId, long sequenceNumber) { return java.util.Optional.empty(); }
            @Override
            public java.util.Optional<Snapshot> findLatestAtOrBeforeTimestamp(UUID aggregateId, Instant timestamp) { return java.util.Optional.empty(); }
        };

        AccountCommandProcessor processorWithFailingRepo = new AccountCommandProcessor(eventStore, new com.fasterxml.jackson.databind.ObjectMapper(), failingRepo, 1);

        // Process command that triggers snapshot interval = 1
        CommandResult result = processorWithFailingRepo.process(new CreateAccount(accountId, "INR", 10000L, 50000L), ctx);

        // Command succeeds despite snapshot failure
        assertThat(result.resultingState().status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(eventStore.currentVersion(accountId)).isEqualTo(1L);

        // State is reconstructable from event store
        TemporalResult reconstructed = temporalReconstructor.reconstructCurrentState(accountId);
        assertThat(reconstructed.reconstructedState().accountId()).isEqualTo(accountId);
    }
}

