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

public class ReplayBenchmark {

    public record SnapshotComparison(
        BenchmarkResult fullReplayResult,
        BenchmarkResult snapshotReplayResult,
        long fullReplayEvents,
        long snapshotReplayEvents,
        long eventsAvoided,
        double replayReductionPercent,
        double wallClockSpeedupMultiplier
    ) {}

    public static SnapshotComparison runBenchmark(
        BenchmarkContext context,
        int datasetSize,
        long snapshotSequence,
        int warmupIterations,
        int measurementIterations,
        BenchmarkEnvironment env
    ) {
        UUID aggregateId = DeterministicEventGenerator.SINGLE_AGGREGATE_ID;

        // 1. Reset benchmark DB & populate single aggregate stream
        BenchmarkDatabaseInitializer.resetBenchmarkDatabase();
        List<DomainEventEnvelope> events = DeterministicEventGenerator.generateSingleAggregateStream(datasetSize, false);
        BenchmarkDatabaseInitializer.batchInsertEvents(events);

        // 2. Insert valid snapshot at configured snapshotSequence
        BenchmarkDatabaseInitializer.createAndInsertSnapshot(aggregateId, events, snapshotSequence);

        // Warmup: Full replay
        for (int i = 0; i < warmupIterations; i++) {
            context.getReconstructor().reconstructFullReplayCurrentState(aggregateId);
        }

        // Measure: Full replay
        List<Double> fullTimings = new ArrayList<>(measurementIterations);
        TemporalResult lastFullResult = null;
        for (int i = 0; i < measurementIterations; i++) {
            long t0 = System.nanoTime();
            lastFullResult = context.getReconstructor().reconstructFullReplayCurrentState(aggregateId);
            long t1 = System.nanoTime();
            fullTimings.add((t1 - t0) / 1_000_000.0);
        }

        BenchmarkResult fullResult = BenchmarkResult.compute(
            "ReplayBenchmark",
            "Full Replay (No Snapshot)",
            datasetSize,
            TopologyType.SINGLE_AGGREGATE,
            0L,
            warmupIterations,
            1,
            "N/A",
            lastFullResult.eventsReplayedCount(),
            1,
            fullTimings,
            "Full aggregate stream load and reduction from PostgreSQL event_store",
            env
        );

        // Warmup: Snapshot replay
        for (int i = 0; i < warmupIterations; i++) {
            context.getReconstructor().reconstructCurrentState(aggregateId);
        }

        // Measure: Snapshot replay
        List<Double> snapshotTimings = new ArrayList<>(measurementIterations);
        TemporalResult lastSnapshotResult = null;
        for (int i = 0; i < measurementIterations; i++) {
            long t0 = System.nanoTime();
            lastSnapshotResult = context.getReconstructor().reconstructCurrentState(aggregateId);
            long t1 = System.nanoTime();
            snapshotTimings.add((t1 - t0) / 1_000_000.0);
        }

        BenchmarkResult snapResult = BenchmarkResult.compute(
            "ReplayBenchmark",
            "Snapshot-Assisted Replay",
            datasetSize,
            TopologyType.SINGLE_AGGREGATE,
            snapshotSequence,
            warmupIterations,
            1,
            "N/A",
            lastSnapshotResult.eventsReplayedCount(),
            1,
            snapshotTimings,
            "Snapshot loaded at sequence " + snapshotSequence + " replaying tail events to latest",
            env
        );

        long fullEvents = lastFullResult.eventsReplayedCount();
        long snapEvents = lastSnapshotResult.eventsReplayedCount();
        long avoided = fullEvents - snapEvents;
        double reductionPct = fullEvents > 0 ? ((double) avoided / fullEvents) * 100.0 : 0.0;
        double speedup = snapResult.medianMillis() > 0 ? fullResult.medianMillis() / snapResult.medianMillis() : 1.0;

        return new SnapshotComparison(
            fullResult,
            snapResult,
            fullEvents,
            snapEvents,
            avoided,
            Math.round(reductionPct * 100.0) / 100.0,
            Math.round(speedup * 100.0) / 100.0
        );
    }
}
