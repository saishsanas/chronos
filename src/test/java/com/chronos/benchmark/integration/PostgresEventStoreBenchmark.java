package com.chronos.benchmark.integration;

import com.chronos.benchmark.dataset.DeterministicEventGenerator;
import com.chronos.benchmark.dataset.TopologyType;
import com.chronos.benchmark.model.BenchmarkEnvironment;
import com.chronos.benchmark.model.BenchmarkResult;
import com.chronos.domain.event.DomainEventEnvelope;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class PostgresEventStoreBenchmark {

    public static BenchmarkResult runLoadStreamBenchmark(
        BenchmarkContext context,
        int datasetSize,
        int warmupIterations,
        int measurementIterations,
        BenchmarkEnvironment env
    ) {
        UUID aggregateId = DeterministicEventGenerator.SINGLE_AGGREGATE_ID;

        // Warmup
        for (int i = 0; i < warmupIterations; i++) {
            context.getEventStore().loadStream(aggregateId);
        }

        // Measure
        List<Double> timings = new ArrayList<>(measurementIterations);
        List<DomainEventEnvelope> lastStream = null;
        for (int i = 0; i < measurementIterations; i++) {
            long t0 = System.nanoTime();
            lastStream = context.getEventStore().loadStream(aggregateId);
            long t1 = System.nanoTime();
            timings.add((t1 - t0) / 1_000_000.0);
        }

        return BenchmarkResult.compute(
            "PostgresEventStoreBenchmark",
            "EventStore loadStream(aggregateId)",
            datasetSize,
            TopologyType.SINGLE_AGGREGATE,
            0L,
            warmupIterations,
            1,
            "N/A",
            lastStream.size(),
            1,
            timings,
            "Direct PostgreSQL SELECT query and Jackson JSON envelope deserialization",
            env
        );
    }
}
