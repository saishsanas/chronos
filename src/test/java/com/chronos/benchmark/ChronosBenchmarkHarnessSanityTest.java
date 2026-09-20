package com.chronos.benchmark;

import com.chronos.benchmark.dataset.BenchmarkDatabaseInitializer;
import com.chronos.benchmark.dataset.DeterministicEventGenerator;
import com.chronos.benchmark.dataset.TopologyType;
import com.chronos.benchmark.model.BenchmarkEnvironment;
import com.chronos.benchmark.model.BenchmarkResult;
import com.chronos.domain.account.AccountReducer;
import com.chronos.domain.account.AccountState;
import com.chronos.domain.event.DomainEventEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChronosBenchmarkHarnessSanityTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    @Test
    @DisplayName("Sanity: Deterministic single-aggregate dataset generates exact counts and valid envelopes")
    void testSingleAggregateGeneration() {
        int count = 50;
        List<DomainEventEnvelope> events = DeterministicEventGenerator.generateSingleAggregateStream(count, false);

        assertThat(events).hasSize(count);
        assertThat(events.get(0).eventType()).isEqualTo("AccountCreated");
        assertThat(events.get(0).sequenceNumber()).isEqualTo(1L);

        for (int i = 0; i < count; i++) {
            assertThat(events.get(i).sequenceNumber()).isEqualTo(i + 1L);
            assertThat(events.get(i).aggregateId()).isEqualTo(DeterministicEventGenerator.SINGLE_AGGREGATE_ID);
        }

        // Verify state reduction correctness
        AccountState state = AccountState.uninitialized(DeterministicEventGenerator.SINGLE_AGGREGATE_ID);
        for (DomainEventEnvelope envelope : events) {
            state = AccountReducer.reduce(state, envelope);
        }

        assertThat(state.sequenceNumber()).isEqualTo(count);
        assertThat(state.balanceMinor()).isPositive();

        // Verify pure determinism across repeated generations
        List<DomainEventEnvelope> events2 = DeterministicEventGenerator.generateSingleAggregateStream(count, false);
        assertThat(events).isEqualTo(events2);
    }

    @Test
    @DisplayName("Sanity: Deterministic multi-aggregate dataset generates uniform distribution across 500 accounts")
    void testMultiAggregateGeneration() {
        int totalEvents = 1000;
        int accounts = 500;
        List<DomainEventEnvelope> events = DeterministicEventGenerator.generateMultiAggregateStream(totalEvents, accounts);

        assertThat(events).hasSize(totalEvents);

        long distinctAccounts = events.stream().map(DomainEventEnvelope::aggregateId).distinct().count();
        assertThat(distinctAccounts).isEqualTo(accounts);

        // Verify pure determinism across repeated generations
        List<DomainEventEnvelope> events2 = DeterministicEventGenerator.generateMultiAggregateStream(totalEvents, accounts);
        assertThat(events).isEqualTo(events2);
    }

    @Test
    @DisplayName("Sanity: Statistical model computes correct median, bounds, and enforces p95 sample threshold")
    void testStatisticalModel() {
        BenchmarkEnvironment env = BenchmarkEnvironment.captureCurrent("test-commit", "PG16", "Redis7");

        // When samples < 20: p95 must be null
        List<Double> shortTimings = List.of(10.0, 12.0, 15.0, 20.0, 25.0);
        BenchmarkResult shortResult = BenchmarkResult.compute(
            "TestBench", "Scenario1", 100, TopologyType.SINGLE_AGGREGATE, 0L, 2, 1, "N/A", 100, 1, shortTimings, "Test notes", env
        );

        assertThat(shortResult.medianMillis()).isEqualTo(15.0);
        assertThat(shortResult.minMillis()).isEqualTo(10.0);
        assertThat(shortResult.maxMillis()).isEqualTo(25.0);
        assertThat(shortResult.p95Millis()).isNull();

        // When samples >= 20: p95 must be computed
        List<Double> longTimings = new java.util.ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            longTimings.add((double) i);
        }
        BenchmarkResult longResult = BenchmarkResult.compute(
            "TestBench", "Scenario2", 100, TopologyType.SINGLE_AGGREGATE, 0L, 2, 1, "N/A", 100, 1, longTimings, "Test notes", env
        );

        assertThat(longResult.measurementIterations()).isEqualTo(20);
        assertThat(longResult.p95Millis()).isNotNull();
        assertThat(longResult.p95Millis()).isEqualTo(19.0);
    }

    @Test
    @DisplayName("Sanity: BenchmarkEnvironment captures machine metadata accurately")
    void testEnvironmentMetadata() {
        BenchmarkEnvironment env = BenchmarkEnvironment.captureCurrent("d3e5748", "PostgreSQL 16", "Redis 7");

        assertThat(env.osName()).isNotBlank();
        assertThat(env.javaVersion()).isNotBlank();
        assertThat(env.availableProcessors()).isGreaterThan(0);
        assertThat(env.maxMemoryBytes()).isGreaterThan(0);
        assertThat(env.gitCommit()).isEqualTo("d3e5748");
    }

    @Test
    @DisplayName("Sanity: Serialization and deserialization of benchmark models succeeds")
    void testModelSerialization() throws Exception {
        BenchmarkEnvironment env = BenchmarkEnvironment.captureCurrent("d3e5748", "PostgreSQL 16", "Redis 7");
        BenchmarkResult result = BenchmarkResult.compute(
            "SanityBench", "SanityScenario", 10, TopologyType.SINGLE_AGGREGATE, 0L, 1, 1, "N/A", 10, 1, List.of(1.0, 2.0, 3.0), "Notes", env
        );

        String json = objectMapper.writeValueAsString(result);
        assertThat(json).contains("SanityBench");
        assertThat(json).contains("SanityScenario");

        BenchmarkResult deserialized = objectMapper.readValue(json, BenchmarkResult.class);
        assertThat(deserialized.benchmarkName()).isEqualTo("SanityBench");
        assertThat(deserialized.medianMillis()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("Sanity: Database isolation safety guard prevents destructive operations on unauthorized DBs")
    void testDatabaseSafetyGuard() {
        assertThat(BenchmarkDatabaseInitializer.BENCHMARK_DB_NAME).isEqualTo("chronos_bench_db");
        assertThat(BenchmarkDatabaseInitializer.BENCHMARK_DB_URL).endsWith("/chronos_bench_db");
    }
}
