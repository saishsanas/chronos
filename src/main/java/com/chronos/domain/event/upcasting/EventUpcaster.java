package com.chronos.domain.event.upcasting;

import com.chronos.domain.event.DomainEventEnvelope;

/**
 * Pure, deterministic contract for evolving an event from sourceVersion to targetVersion.
 * Implementations must have no external state, database calls, randomness, or clock dependencies.
 */
public interface EventUpcaster {

    String eventType();

    int sourceVersion();

    int targetVersion();

    DomainEventEnvelope upcast(DomainEventEnvelope event);
}
