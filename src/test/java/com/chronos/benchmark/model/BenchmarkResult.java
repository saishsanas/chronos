package com.chronos.benchmark.model;

import com.chronos.benchmark.dataset.TopologyType;
import java.util.Collections;
import java.util.List;

public record BenchmarkResult(
    String benchmarkVersion,
    String benchmarkName,
    String scenario,
    int datasetSize,
    TopologyType topology,
    long snapshotSequence,
    int warmupIterations,
    int measurementIterations,
    int concurrency,
    String cacheState,
    long eventsProcessed,
    int accountsProcessed,
    double durationMillis,
    double meanMillis,
    double medianMillis,
    double minMillis,
    double maxMillis,
    double stdDevMillis,
    Double p95Millis,
    double throughputOpsPerSec,
    String notes,
    List<Double> rawTimingsMillis,
    BenchmarkEnvironment environment
) {
    public static BenchmarkResult compute(
        String benchmarkName,
        String scenario,
        int datasetSize,
        TopologyType topology,
        long snapshotSequence,
        int warmupIterations,
        int concurrency,
        String cacheState,
        long eventsProcessed,
        int accountsProcessed,
        List<Double> timingsMillis,
        String notes,
        BenchmarkEnvironment env
    ) {
        if (timingsMillis == null || timingsMillis.isEmpty()) {
            throw new IllegalArgumentException("timingsMillis must contain at least one observation");
        }

        List<Double> sorted = timingsMillis.stream().sorted().toList();
        int n = sorted.size();
        double sum = sorted.stream().mapToDouble(Double::doubleValue).sum();
        double mean = sum / n;
        double min = sorted.get(0);
        double max = sorted.get(n - 1);

        double median;
        if (n % 2 == 1) {
            median = sorted.get(n / 2);
        } else {
            median = (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
        }

        double variance = sorted.stream()
            .mapToDouble(v -> Math.pow(v - mean, 2))
            .sum() / n;
        double stdDev = Math.sqrt(variance);

        // Per Correction 1: Report p95 ONLY when at least 20 measured observations exist
        Double p95 = null;
        if (n >= 20) {
            int p95Index = (int) Math.ceil(0.95 * n) - 1;
            p95 = sorted.get(Math.min(p95Index, n - 1));
        }

        double totalDuration = sum;
        // Throughput in events or operations per second based on median
        double secondsPerOp = (median / 1000.0);
        double throughput = secondsPerOp > 0 ? (eventsProcessed > 0 ? eventsProcessed / secondsPerOp : 1.0 / secondsPerOp) : 0;

        return new BenchmarkResult(
            env != null ? env.benchmarkVersion() : "1.0.0",
            benchmarkName,
            scenario,
            datasetSize,
            topology,
            snapshotSequence,
            warmupIterations,
            n,
            concurrency,
            cacheState,
            eventsProcessed,
            accountsProcessed,
            totalDuration,
            round(mean, 3),
            round(median, 3),
            round(min, 3),
            round(max, 3),
            round(stdDev, 3),
            p95 != null ? round(p95, 3) : null,
            round(throughput, 2),
            notes != null ? notes : "",
            Collections.unmodifiableList(sorted),
            env
        );
    }

    private static double round(double val, int decimals) {
        double factor = Math.pow(10, decimals);
        return Math.round(val * factor) / factor;
    }
}
