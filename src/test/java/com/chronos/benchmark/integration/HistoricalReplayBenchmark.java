package com.chronos.benchmark.integration;

import com.chronos.application.model.TemporalResult;
import com.chronos.benchmark.dataset.DeterministicEventGenerator;
import com.chronos.benchmark.dataset.TopologyType;
import com.chronos.benchmark.model.BenchmarkEnvironment;
import com.chronos.benchmark.model.BenchmarkResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class HistoricalReplayBenchmark {

    public static BenchmarkResult runBenchmark(
        BenchmarkContext context,
        int datasetSize,
        int warmupIterations,
        int measurementIterations,
        BenchmarkEnvironment env
    ) {
        UUID aggregateId = DeterministicEventGenerator.SINGLE_AGGREGATE_ID;
        // Historical point in time T chosen at exactly 50% of the stream
        long midSequence = datasetSize / 2;
        Instant targetTimestamp = DeterministicEventGenerator.BASE_TIMESTAMP.plusSeconds(midSequence * 10L);

        // Warmup
        for (int i = 0; i < warmupIterations; i++) {
            context.getReconstructor().reconstructFullReplayStateAt(aggregateId, targetTimestamp);
        }

        // Measure
        List<Double> timings = new ArrayList<>(measurementIterations);
        TemporalResult lastResult = null;
        for (int i = 0; i < measurementIterations; i++) {
            long t0 = System.nanoTime();
            lastResult = context.getReconstructor().reconstructFullReplayStateAt(aggregateId, targetTimestamp);
            long t1 = System.nanoTime();
            timings.add((t1 - t0) / 1_000_000.0);
        }

        return BenchmarkResult.compute(
            "HistoricalReplayBenchmark",
            "Temporal stateAt(T) Replay (recordedAt <= T)",
            datasetSize,
            TopologyType.SINGLE_AGGREGATE,
            0L,
            warmupIterations,
            1,
            "N/A",
            lastResult.eventsReplayedCount(),
            1,
            timings,
            "Temporal reconstruction at T=" + targetTimestamp + " (replayed " + lastResult.eventsReplayedCount() + " of " + datasetSize + " events)",
            env
        );
    }
}
