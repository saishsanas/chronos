package com.chronos.benchmark.integration;

import com.chronos.application.command.CommandContext;
import com.chronos.application.command.CommandResult;
import com.chronos.benchmark.dataset.DeterministicEventGenerator;
import com.chronos.benchmark.dataset.TopologyType;
import com.chronos.benchmark.model.BenchmarkEnvironment;
import com.chronos.benchmark.model.BenchmarkResult;
import com.chronos.domain.account.command.CreateAccount;
import com.chronos.domain.account.command.DepositMoney;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class CommandWriteBenchmark {

    public static BenchmarkResult runBenchmark(
        BenchmarkContext context,
        int operationsCount,
        int warmupIterations,
        int measurementIterations,
        BenchmarkEnvironment env
    ) {
        UUID actorId = UUID.nameUUIDFromBytes("benchmark-authenticated-operator".getBytes());
        UUID accountId = DeterministicEventGenerator.getMultiAggregateId(1);

        // Ensure target account exists
        if (context.getEventStore().loadStream(accountId).isEmpty()) {
            CreateAccount createCmd = new CreateAccount(accountId, "INR", 1_000_000L, 10_000_000L);
            CommandContext createCtx = CommandContext.of(actorId.toString(), "init-cmd-bench-" + accountId);
            context.getCommandProcessor().process(createCmd, createCtx);
        }

        // Warmup: Execute deposit commands
        for (int i = 0; i < warmupIterations; i++) {
            DepositMoney cmd = new DepositMoney(accountId, 100L, "BENCHMARK");
            CommandContext ctx = CommandContext.of(actorId.toString(), "warmup-idemp-" + UUID.randomUUID());
            context.getCommandProcessor().process(cmd, ctx);
        }

        // Measure: Execute distinct deposit commands with unique idempotency keys
        List<Double> timings = new ArrayList<>(measurementIterations);
        for (int i = 0; i < measurementIterations; i++) {
            DepositMoney cmd = new DepositMoney(accountId, 100L, "BENCHMARK");
            CommandContext ctx = CommandContext.of(actorId.toString(), "measured-idemp-" + UUID.randomUUID());

            long t0 = System.nanoTime();
            CommandResult result = context.getCommandProcessor().process(cmd, ctx);
            long t1 = System.nanoTime();

            if (result == null || result.emittedEvents().isEmpty()) {
                throw new IllegalStateException("Command processor produced no events");
            }
            timings.add((t1 - t0) / 1_000_000.0);
        }

        return BenchmarkResult.compute(
            "CommandWriteBenchmark",
            "Synchronous Command Write (DepositMoney: Validation + OCC + Idempotency + EventStore + Outbox)",
            operationsCount,
            TopologyType.SINGLE_AGGREGATE,
            0L,
            warmupIterations,
            1,
            "N/A",
            1,
            1,
            timings,
            "Synchronous financial transaction persisting to PostgreSQL event_store and outbox_events atomically",
            env
        );
    }
}
