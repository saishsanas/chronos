package com.chronos.benchmark.integration;

import com.chronos.api.dto.AccountSummaryResponse;
import com.chronos.benchmark.dataset.DeterministicEventGenerator;
import com.chronos.benchmark.dataset.TopologyType;
import com.chronos.benchmark.model.BenchmarkEnvironment;
import com.chronos.benchmark.model.BenchmarkResult;
import com.chronos.domain.projection.AccountSummaryProjection;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class ReadModelQueryBenchmark {

    public record ReadModelSuite(
        BenchmarkResult postgresDirectResult,
        BenchmarkResult redisMissFallbackResult,
        BenchmarkResult redisHitResult
    ) {}

    public static ReadModelSuite runBenchmark(
        BenchmarkContext context,
        int warmupIterations,
        int measurementIterations,
        BenchmarkEnvironment env
    ) {
        UUID accountId = DeterministicEventGenerator.getMultiAggregateId(0);

        // Ensure projection exists in Postgres
        Optional<AccountSummaryProjection> proj = context.getProjectionRepository().findByAccountId(accountId);
        if (proj.isEmpty()) {
            // Rebuild projection so it exists
            context.getProjectionRebuildService().rebuildFull();
        }

        // ==========================================
        // 1. PostgreSQL Direct Projection Lookup
        // ==========================================
        for (int i = 0; i < warmupIterations; i++) {
            context.getProjectionRepository().findByAccountId(accountId);
        }

        List<Double> pgTimings = new ArrayList<>(measurementIterations);
        for (int i = 0; i < measurementIterations; i++) {
            long t0 = System.nanoTime();
            context.getProjectionRepository().findByAccountId(accountId);
            long t1 = System.nanoTime();
            pgTimings.add((t1 - t0) / 1_000_000.0);
        }

        BenchmarkResult pgResult = BenchmarkResult.compute(
            "ReadModelQueryBenchmark",
            "PostgreSQL Direct Projection Read",
            1,
            TopologyType.SINGLE_AGGREGATE,
            0L,
            warmupIterations,
            1,
            "N/A",
            1,
            1,
            pgTimings,
            "Direct query to account_summary_projection without Redis cache layer",
            env
        );

        // ==========================================
        // 2. Redis Miss -> PostgreSQL Fallback
        // ==========================================
        for (int i = 0; i < warmupIterations; i++) {
            context.getRedisCacheService().evict(accountId);
            context.getAccountSummaryQueryService().getAccountSummary(accountId);
        }

        List<Double> missTimings = new ArrayList<>(measurementIterations);
        for (int i = 0; i < measurementIterations; i++) {
            context.getRedisCacheService().evict(accountId);
            long t0 = System.nanoTime();
            context.getAccountSummaryQueryService().getAccountSummary(accountId);
            long t1 = System.nanoTime();
            missTimings.add((t1 - t0) / 1_000_000.0);
        }

        BenchmarkResult missResult = BenchmarkResult.compute(
            "ReadModelQueryBenchmark",
            "Redis Cache Miss + PostgreSQL Fallback",
            1,
            TopologyType.SINGLE_AGGREGATE,
            0L,
            warmupIterations,
            1,
            "MISS",
            1,
            1,
            missTimings,
            "AccountSummaryQueryService cache miss: reads Postgres and populates Redis",
            env
        );

        // ==========================================
        // 3. Redis Cache Hit
        // ==========================================
        // Ensure cache is populated
        context.getAccountSummaryQueryService().getAccountSummary(accountId);

        for (int i = 0; i < warmupIterations; i++) {
            context.getAccountSummaryQueryService().getAccountSummary(accountId);
        }

        List<Double> hitTimings = new ArrayList<>(measurementIterations);
        for (int i = 0; i < measurementIterations; i++) {
            long t0 = System.nanoTime();
            AccountSummaryResponse resp = context.getAccountSummaryQueryService().getAccountSummary(accountId);
            long t1 = System.nanoTime();
            if (resp == null) throw new IllegalStateException("Redis cache returned null response");
            hitTimings.add((t1 - t0) / 1_000_000.0);
        }

        BenchmarkResult hitResult = BenchmarkResult.compute(
            "ReadModelQueryBenchmark",
            "Redis Cache Hit",
            1,
            TopologyType.SINGLE_AGGREGATE,
            0L,
            warmupIterations,
            1,
            "HIT",
            1,
            1,
            hitTimings,
            "AccountSummaryQueryService low-latency Redis read with deserialization",
            env
        );

        return new ReadModelSuite(pgResult, missResult, hitResult);
    }
}
