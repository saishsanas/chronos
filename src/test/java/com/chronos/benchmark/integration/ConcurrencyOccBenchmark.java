package com.chronos.benchmark.integration;

import com.chronos.application.command.CommandContext;
import com.chronos.application.command.CommandResult;
import com.chronos.application.port.OptimisticConcurrencyException;
import com.chronos.benchmark.dataset.DeterministicEventGenerator;
import com.chronos.benchmark.dataset.TopologyType;
import com.chronos.benchmark.model.BenchmarkEnvironment;
import com.chronos.benchmark.model.BenchmarkResult;
import com.chronos.domain.account.command.DepositMoney;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ConcurrencyOccBenchmark {

    public record ConcurrencySuite(
        List<BenchmarkResult> independentWorkerScaling,
        BenchmarkResult occConflictContention
    ) {}

    public static ConcurrencySuite runBenchmark(
        BenchmarkContext context,
        BenchmarkEnvironment env
    ) {
        UUID actorId = UUID.nameUUIDFromBytes("benchmark-authenticated-operator".getBytes());
        int[] concurrencyLevels = {1, 2, 4, 8};
        int operationsPerWorker = 20;

        List<BenchmarkResult> scalingResults = new ArrayList<>();

        // Part 1: Independent aggregates (Throughput scaling)
        for (int workers : concurrencyLevels) {
            ExecutorService executor = Executors.newFixedThreadPool(workers);
            List<Callable<Double>> tasks = new ArrayList<>();

            for (int w = 0; w < workers; w++) {
                final int workerIdx = w;
                UUID targetAccount = DeterministicEventGenerator.getMultiAggregateId(workerIdx + 10);

                tasks.add(() -> {
                    long start = System.nanoTime();
                    for (int op = 0; op < operationsPerWorker; op++) {
                        DepositMoney cmd = new DepositMoney(targetAccount, 50L, "CONC_WORKER_" + workerIdx);
                        CommandContext ctx = CommandContext.of(actorId.toString(), "conc-idemp-" + UUID.randomUUID());
                        context.getCommandProcessor().process(cmd, ctx);
                    }
                    long end = System.nanoTime();
                    return (end - start) / 1_000_000.0;
                });
            }

            try {
                long t0 = System.nanoTime();
                List<Future<Double>> futures = executor.invokeAll(tasks);
                List<Double> workerDurations = new ArrayList<>();
                for (Future<Double> f : futures) {
                    workerDurations.add(f.get());
                }
                long t1 = System.nanoTime();
                double totalElapsedMillis = (t1 - t0) / 1_000_000.0;

                executor.shutdown();
                executor.awaitTermination(30, TimeUnit.SECONDS);

                long totalOps = (long) workers * operationsPerWorker;
                BenchmarkResult res = BenchmarkResult.compute(
                    "ConcurrencyOccBenchmark",
                    "Concurrent Writes (Independent Accounts - " + workers + " Workers)",
                    (int) totalOps,
                    TopologyType.MULTI_AGGREGATE,
                    0L,
                    1,
                    workers,
                    "N/A",
                    totalOps,
                    workers,
                    workerDurations,
                    "Throughput scaling across independent aggregates with " + workers + " parallel threads",
                    env
                );
                scalingResults.add(res);

            } catch (Exception e) {
                throw new RuntimeException("Concurrent benchmark failed: " + e.getMessage(), e);
            }
        }

        // Part 2: OCC Conflict Contention on Same Aggregate
        UUID sharedAccount = DeterministicEventGenerator.getMultiAggregateId(5);
        int contentionWorkers = 8;
        int attemptsPerWorker = 10;
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger occConflictCount = new AtomicInteger(0);

        ExecutorService contentionExecutor = Executors.newFixedThreadPool(contentionWorkers);
        List<Callable<Double>> contentionTasks = new ArrayList<>();

        for (int w = 0; w < contentionWorkers; w++) {
            final int workerIdx = w;
            contentionTasks.add(() -> {
                long start = System.nanoTime();
                for (int op = 0; op < attemptsPerWorker; op++) {
                    DepositMoney cmd = new DepositMoney(sharedAccount, 10L, "OCC_WORKER_" + workerIdx);
                    CommandContext ctx = CommandContext.of(actorId.toString(), "occ-idemp-" + UUID.randomUUID());
                    try {
                        CommandResult cr = context.getCommandProcessor().process(cmd, ctx);
                        if (cr != null) successCount.incrementAndGet();
                    } catch (OptimisticConcurrencyException occ) {
                        occConflictCount.incrementAndGet();
                    } catch (Exception ignored) {
                    }
                }
                long end = System.nanoTime();
                return (end - start) / 1_000_000.0;
            });
        }

        List<Double> contentionDurations = new ArrayList<>();
        try {
            List<Future<Double>> futures = contentionExecutor.invokeAll(contentionTasks);
            for (Future<Double> f : futures) {
                contentionDurations.add(f.get());
            }
            contentionExecutor.shutdown();
            contentionExecutor.awaitTermination(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("Contention benchmark failed: " + e.getMessage(), e);
        }

        BenchmarkResult conflictResult = BenchmarkResult.compute(
            "ConcurrencyOccBenchmark",
            "OCC Contention on Single Account (" + contentionWorkers + " Workers)",
            contentionWorkers * attemptsPerWorker,
            TopologyType.SINGLE_AGGREGATE,
            0L,
            0,
            contentionWorkers,
            "N/A",
            successCount.get(),
            1,
            contentionDurations,
            "Controlled OCC collision test: " + successCount.get() + " committed, " + occConflictCount.get() + " expected OCC conflicts",
            env
        );

        return new ConcurrencySuite(scalingResults, conflictResult);
    }
}
