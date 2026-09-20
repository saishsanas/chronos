package com.chronos.benchmark.micro;

import com.chronos.benchmark.dataset.DeterministicEventGenerator;
import com.chronos.domain.account.AccountReducer;
import com.chronos.domain.account.AccountState;
import com.chronos.domain.event.DomainEventEnvelope;
import com.chronos.domain.event.upcasting.EventUpcasterRegistry;
import com.chronos.domain.event.upcasting.MoneyDepositedV1ToV2Upcaster;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class AccountReducerBenchmark {

    private AccountState baseState;
    private DomainEventEnvelope depositV2;
    private DomainEventEnvelope withdrawal;
    private DomainEventEnvelope depositV1;
    private MoneyDepositedV1ToV2Upcaster upcaster;
    private EventUpcasterRegistry registry;

    @Setup(Level.Trial)
    public void setup() {
        List<DomainEventEnvelope> events = DeterministicEventGenerator.generateSingleAggregateStream(10, true);
        UUID aggregateId = DeterministicEventGenerator.SINGLE_AGGREGATE_ID;

        baseState = AccountState.uninitialized(aggregateId);
        baseState = AccountReducer.reduce(baseState, events.get(0)); // AccountCreated

        depositV2 = events.stream().filter(e -> "MoneyDeposited".equals(e.eventType()) && e.eventVersion() == 2).findFirst().orElseThrow();
        depositV1 = events.stream().filter(e -> "MoneyDeposited".equals(e.eventType()) && e.eventVersion() == 1).findFirst().orElseThrow();
        withdrawal = events.stream().filter(e -> "MoneyWithdrawn".equals(e.eventType())).findFirst().orElseThrow();

        upcaster = new MoneyDepositedV1ToV2Upcaster();
        registry = EventUpcasterRegistry.getInstance();
    }

    @Benchmark
    public AccountState measureReduceDepositV2() {
        return AccountReducer.reduce(baseState, depositV2);
    }

    @Benchmark
    public AccountState measureReduceWithdrawal() {
        return AccountReducer.reduce(baseState, withdrawal);
    }

    @Benchmark
    public DomainEventEnvelope measureUpcastV1ToV2Direct() {
        return upcaster.upcast(depositV1);
    }

    @Benchmark
    public DomainEventEnvelope measureRegistryUpcastCanonicalV1() {
        return registry.upcastToCanonical(depositV1);
    }

    @Benchmark
    public DomainEventEnvelope measureRegistryNoOpCanonicalV2() {
        return registry.upcastToCanonical(depositV2);
    }

    public static void main(String[] args) throws Exception {
        Options opt = new OptionsBuilder()
            .include(AccountReducerBenchmark.class.getSimpleName())
            .forks(0)
            .warmupIterations(2)
            .measurementIterations(3)
            .build();
        new Runner(opt).run();
    }
}
