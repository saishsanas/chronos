package com.chronos.benchmark.integration;

import com.chronos.application.model.TemporalResult;
import com.chronos.benchmark.dataset.BenchmarkDatabaseInitializer;
import com.chronos.benchmark.dataset.DeterministicEventGenerator;
import com.chronos.benchmark.dataset.TopologyType;
import com.chronos.benchmark.model.BenchmarkEnvironment;
import com.chronos.benchmark.model.BenchmarkResult;
import com.chronos.domain.event.DomainEventEnvelope;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class SchemaEvolutionBenchmark {

    public record SchemaEvolutionSuite(
        BenchmarkResult mixedStreamReplayResult,
        boolean rawEventsUnmodified
    ) {}

    public static SchemaEvolutionSuite runBenchmark(
        BenchmarkContext context,
        int datasetSize,
        int warmupIterations,
        int measurementIterations,
        BenchmarkEnvironment env
    ) {
        UUID aggregateId = DeterministicEventGenerator.SINGLE_AGGREGATE_ID;

        // 1. Reset benchmark DB & insert mixed stream (v1 and v2 events)
        BenchmarkDatabaseInitializer.resetBenchmarkDatabase();
        List<DomainEventEnvelope> mixedEvents = DeterministicEventGenerator.generateSingleAggregateStream(datasetSize, true);
        BenchmarkDatabaseInitializer.batchInsertEvents(mixedEvents);

        // Verify initial count of v1 events in event_store
        int initialV1Count = context.getJdbcTemplate().queryForObject(
            "SELECT COUNT(*) FROM event_store WHERE aggregate_id = ? AND event_version = 1 AND event_type = 'MoneyDeposited'",
            Integer.class,
            aggregateId
        );

        // Warmup
        for (int i = 0; i < warmupIterations; i++) {
            context.getReconstructor().reconstructFullReplayCurrentState(aggregateId);
        }

        // Measure
        List<Double> timings = new ArrayList<>(measurementIterations);
        TemporalResult lastResult = null;
        for (int i = 0; i < measurementIterations; i++) {
            long t0 = System.nanoTime();
            lastResult = context.getReconstructor().reconstructFullReplayCurrentState(aggregateId);
            long t1 = System.nanoTime();
            timings.add((t1 - t0) / 1_000_000.0);
        }

        // Verify post-replay immutability: event_store MUST NOT have mutated raw events!
        int postReplayV1Count = context.getJdbcTemplate().queryForObject(
            "SELECT COUNT(*) FROM event_store WHERE aggregate_id = ? AND event_version = 1 AND event_type = 'MoneyDeposited'",
            Integer.class,
            aggregateId
        );

        boolean immutabilityPreserved = (initialV1Count > 0 && initialV1Count == postReplayV1Count);

        BenchmarkResult res = BenchmarkResult.compute(
            "SchemaEvolutionBenchmark",
            "Mixed Stream Replay (Deterministic v1 -> v2 Upcasting)",
            datasetSize,
            TopologyType.SINGLE_AGGREGATE,
            0L,
            warmupIterations,
            1,
            "N/A",
            lastResult.eventsReplayedCount(),
            1,
            timings,
            "Upcasts legacy v1 events in memory during stream load; raw PostgreSQL event_store remains strictly immutable (v1 count: " + initialV1Count + ")",
            env
        );

        return new SchemaEvolutionSuite(res, immutabilityPreserved);
    }
}
