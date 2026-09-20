package com.chronos.benchmark.integration;

import com.chronos.benchmark.dataset.BenchmarkDatabaseInitializer;
import com.chronos.benchmark.dataset.DeterministicEventGenerator;
import com.chronos.benchmark.dataset.TopologyType;
import com.chronos.benchmark.model.BenchmarkEnvironment;
import com.chronos.benchmark.model.BenchmarkResult;
import com.chronos.domain.event.DomainEventEnvelope;
import com.chronos.domain.projection.ProjectionRebuildJob;
import com.chronos.domain.projection.RebuildStatus;

import java.util.ArrayList;
import java.util.List;

public class ProjectionRebuildBenchmark {

    public static BenchmarkResult runBenchmark(
        BenchmarkContext context,
        int datasetSize,
        int warmupIterations,
        int measurementIterations,
        BenchmarkEnvironment env
    ) {
        int accountCount = DeterministicEventGenerator.MULTI_AGGREGATE_ACCOUNT_COUNT;

        // 1. Reset benchmark DB & populate multi-aggregate stream
        BenchmarkDatabaseInitializer.resetBenchmarkDatabase();
        List<DomainEventEnvelope> events = DeterministicEventGenerator.generateMultiAggregateStream(datasetSize, accountCount);
        BenchmarkDatabaseInitializer.batchInsertEvents(events);

        // Warmup: Run rebuild
        for (int i = 0; i < warmupIterations; i++) {
            ProjectionRebuildJob job = context.getProjectionRebuildService().rebuildFull();
            if (job.status() != RebuildStatus.SUCCEEDED) {
                throw new IllegalStateException("Warmup projection rebuild failed: " + job.errorDetails());
            }
        }

        // Measure: Run rebuild
        List<Double> timings = new ArrayList<>(measurementIterations);
        ProjectionRebuildJob lastJob = null;
        for (int i = 0; i < measurementIterations; i++) {
            long t0 = System.nanoTime();
            lastJob = context.getProjectionRebuildService().rebuildFull();
            long t1 = System.nanoTime();
            if (lastJob.status() != RebuildStatus.SUCCEEDED) {
                throw new IllegalStateException("Measured projection rebuild failed: " + lastJob.errorDetails());
            }
            timings.add((t1 - t0) / 1_000_000.0);
        }

        return BenchmarkResult.compute(
            "ProjectionRebuildBenchmark",
            "Full Projection Rebuild (Staging + Atomic Cutover)",
            datasetSize,
            TopologyType.MULTI_AGGREGATE,
            0L,
            warmupIterations,
            1,
            "EVICTED",
            datasetSize,
            accountCount,
            timings,
            "Rebuild from EventStore into staging table, atomic swap, and Redis cache eviction across " + accountCount + " accounts",
            env
        );
    }
}
