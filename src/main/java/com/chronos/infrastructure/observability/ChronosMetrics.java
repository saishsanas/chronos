package com.chronos.infrastructure.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Objects;

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

    public void recordEventStoreAppend(boolean success) {
        meterRegistry.counter("chronos.eventstore.append", "outcome", success ? "SUCCESS" : "FAILURE").increment();
    }

    public void recordTemporalReconstruction(boolean snapshotUsed) {
        meterRegistry.counter("chronos.temporal.reconstruction", "snapshotUsed", String.valueOf(snapshotUsed)).increment();
    }

    public void recordOutboxPublished() {
        meterRegistry.counter("chronos.outbox.published").increment();
    }

    public void recordOutboxFailed() {
        meterRegistry.counter("chronos.outbox.failed").increment();
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

    public void recordProjectionProcessed() {
        meterRegistry.counter("chronos.projection.processed").increment();
    }

    public void recordProjectionFailed() {
        meterRegistry.counter("chronos.projection.failed").increment();
    }

    public void recordProjectionDuplicate() {
        meterRegistry.counter("chronos.projection.duplicates").increment();
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
