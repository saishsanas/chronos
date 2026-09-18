package com.chronos.infrastructure.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Component
public class ChronosMetrics {

    private final MeterRegistry meterRegistry;

    public ChronosMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
    }

    public void recordCommandProcessed(String commandName) {
        meterRegistry.counter("chronos.commands.processed", "command", sanitize(commandName), "outcome", "SUCCESS").increment();
    }

    public void recordCommandFailed(String commandName, String reason) {
        meterRegistry.counter("chronos.commands.failed", "command", sanitize(commandName), "outcome", sanitize(reason)).increment();
    }

    public void recordCommandIdempotencyDuplicate() {
        meterRegistry.counter("chronos.command.idempotency.duplicate").increment();
    }

    public void recordCommandIdempotencyConflict() {
        meterRegistry.counter("chronos.command.idempotency.conflict").increment();
    }

    public void recordEventStoreAppend(boolean success) {
        meterRegistry.counter("chronos.eventstore.append", "outcome", success ? "SUCCESS" : "FAILURE").increment();
    }

    public void recordOccConflict() {
        meterRegistry.counter("chronos.occ.conflict").increment();
    }

    public void recordTemporalReconstruction(boolean snapshotUsed) {
        meterRegistry.counter("chronos.temporal.reconstruction", "snapshotUsed", String.valueOf(snapshotUsed)).increment();
    }

    public void recordSnapshotFallback() {
        meterRegistry.counter("chronos.snapshot.fallback").increment();
    }

    public void recordReplayFailure() {
        meterRegistry.counter("chronos.replay.failure").increment();
    }

    public void recordOutboxPublished() {
        meterRegistry.counter("chronos.outbox.published").increment();
    }

    public void recordOutboxFailed() {
        meterRegistry.counter("chronos.outbox.failed").increment();
    }

    public void recordOutboxRetry() {
        meterRegistry.counter("chronos.outbox.retry").increment();
    }

    public void recordOutboxRecovery() {
        meterRegistry.counter("chronos.outbox.recovery").increment();
    }

    public void recordInboxProcessed() {
        meterRegistry.counter("chronos.inbox.processed").increment();
    }

    public void recordInboxDuplicate() {
        meterRegistry.counter("chronos.inbox.duplicates").increment();
    }

    public void recordInboxFailed() {
        meterRegistry.counter("chronos.inbox.failed").increment();
    }

    public void recordInboxPoison() {
        meterRegistry.counter("chronos.inbox.poison").increment();
    }

    public void recordProjectionProcessed() {
        meterRegistry.counter("chronos.projection.processed").increment();
    }

    public void recordProjectionFailed() {
        meterRegistry.counter("chronos.projection.failed").increment();
    }

    public void recordProjectionDuplicate() {
        meterRegistry.counter("chronos.projection.duplicates").increment();
    }

    public void recordProjectionRebuildSuccess(Duration duration) {
        meterRegistry.counter("chronos.projection.rebuild.success").increment();
        if (duration != null) {
            Timer.builder("chronos.projection.rebuild.duration")
                .register(meterRegistry)
                .record(duration.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    public void recordProjectionRebuildFailure() {
        meterRegistry.counter("chronos.projection.rebuild.failure").increment();
    }

    public void recordCacheHit() {
        meterRegistry.counter("chronos.cache.hit").increment();
    }

    public void recordCacheMiss() {
        meterRegistry.counter("chronos.cache.miss").increment();
    }

    public void recordCacheFailure() {
        meterRegistry.counter("chronos.cache.failure").increment();
    }

    private String sanitize(String input) {
        if (input == null || input.isBlank()) return "UNKNOWN";
        return input.replaceAll("[^a-zA-Z0-9_.-]", "_");
    }
}
