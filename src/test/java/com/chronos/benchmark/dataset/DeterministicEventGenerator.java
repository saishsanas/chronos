package com.chronos.benchmark.dataset;

import com.chronos.domain.event.DomainEventEnvelope;
import com.chronos.domain.event.EventMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class DeterministicEventGenerator {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    public static final Instant BASE_TIMESTAMP = Instant.parse("2026-01-01T00:00:00Z");
    public static final UUID SINGLE_AGGREGATE_ID = UUID.nameUUIDFromBytes("chronos-bench-single-account".getBytes(StandardCharsets.UTF_8));
    public static final int MULTI_AGGREGATE_ACCOUNT_COUNT = 500;

    /**
     * Topology A: Single aggregate with sequence 1..totalEvents
     */
    public static List<DomainEventEnvelope> generateSingleAggregateStream(int totalEvents, boolean includeV1LegacyEvents) {
        if (totalEvents < 1) {
            throw new IllegalArgumentException("totalEvents must be >= 1");
        }

        List<DomainEventEnvelope> events = new ArrayList<>(totalEvents);
        UUID aggregateId = SINGLE_AGGREGATE_ID;

        // Sequence 1: AccountCreated
        Instant t1 = BASE_TIMESTAMP;
        ObjectNode createPayload = MAPPER.createObjectNode()
            .put("currency", "INR")
            .put("initialOverdraftLimitMinor", 1_000_000L)
            .put("initialTransactionLimitMinor", 10_000_000L);

            UUID corrId = UUID.nameUUIDFromBytes((aggregateId + "-corr-1").getBytes(StandardCharsets.UTF_8));
            UUID causId = UUID.nameUUIDFromBytes((aggregateId + "-caus-1").getBytes(StandardCharsets.UTF_8));
            events.add(new DomainEventEnvelope(
                UUID.nameUUIDFromBytes((aggregateId + "-seq-1").getBytes(StandardCharsets.UTF_8)),
                aggregateId,
                "Account",
                1L,
                "AccountCreated",
                1,
                t1,
                new EventMetadata(corrId, causId, "bench-actor", "bench-idemp-1"),
                createPayload
            ));

            // Sequence 2..totalEvents
            for (int seq = 2; seq <= totalEvents; seq++) {
                Instant t = BASE_TIMESTAMP.plusSeconds((long) seq * 10L);
                UUID eventId = UUID.nameUUIDFromBytes((aggregateId + "-seq-" + seq).getBytes(StandardCharsets.UTF_8));
                UUID cId = UUID.nameUUIDFromBytes((aggregateId + "-corr-" + seq).getBytes(StandardCharsets.UTF_8));
                UUID caId = UUID.nameUUIDFromBytes((aggregateId + "-caus-" + seq).getBytes(StandardCharsets.UTF_8));
                EventMetadata meta = new EventMetadata(cId, caId, "bench-actor", "bench-idemp-" + seq);

            // Alternate deposits and withdrawals: every 5th event is withdrawal, rest deposits
            if (seq % 5 == 0) {
                ObjectNode wthPayload = MAPPER.createObjectNode()
                    .put("amountMinor", 200L)
                    .put("currency", "INR")
                    .put("reference", "BENCH-WTH-" + seq);

                events.add(new DomainEventEnvelope(
                    eventId,
                    aggregateId,
                    "Account",
                    (long) seq,
                    "MoneyWithdrawn",
                    1,
                    t,
                    meta,
                    wthPayload
                ));
            } else {
                boolean useV1 = includeV1LegacyEvents && (seq % 2 == 0);
                ObjectNode depPayload = MAPPER.createObjectNode()
                    .put("amountMinor", 1000L)
                    .put("currency", "INR")
                    .put("reference", "BENCH-DEP-" + seq);

                if (!useV1) {
                    depPayload.put("source", "BENCHMARK");
                    depPayload.put("recordedAt", t.toString());
                }

                events.add(new DomainEventEnvelope(
                    eventId,
                    aggregateId,
                    "Account",
                    (long) seq,
                    "MoneyDeposited",
                    useV1 ? 1 : 2,
                    t,
                    meta,
                    depPayload
                ));
            }
        }

        return events;
    }

    /**
     * Topology B: Multi-aggregate stream across fixed 500 accounts
     */
    public static List<DomainEventEnvelope> generateMultiAggregateStream(int totalEvents, int accountCount) {
        if (totalEvents < accountCount) {
            throw new IllegalArgumentException("totalEvents (" + totalEvents + ") must be >= accountCount (" + accountCount + ")");
        }

        int eventsPerAccount = totalEvents / accountCount;
        List<DomainEventEnvelope> events = new ArrayList<>(totalEvents);

        for (int accIdx = 0; accIdx < accountCount; accIdx++) {
            UUID accountId = getMultiAggregateId(accIdx);

            // Sequence 1: AccountCreated
            Instant t1 = BASE_TIMESTAMP.plusSeconds(accIdx);
            ObjectNode createPayload = MAPPER.createObjectNode()
                .put("currency", "INR")
                .put("initialOverdraftLimitMinor", 100_000L)
                .put("initialTransactionLimitMinor", 500_000L);

            UUID corrId1 = UUID.nameUUIDFromBytes((accountId + "-corr-1").getBytes(StandardCharsets.UTF_8));
            UUID causId1 = UUID.nameUUIDFromBytes((accountId + "-caus-1").getBytes(StandardCharsets.UTF_8));
            events.add(new DomainEventEnvelope(
                UUID.nameUUIDFromBytes((accountId + "-seq-1").getBytes(StandardCharsets.UTF_8)),
                accountId,
                "Account",
                1L,
                "AccountCreated",
                1,
                t1,
                new EventMetadata(corrId1, causId1, "bench-actor", "bench-multi-idemp-1"),
                createPayload
            ));

            for (int seq = 2; seq <= eventsPerAccount; seq++) {
                Instant t = BASE_TIMESTAMP.plusSeconds((long) (accIdx * 1000L + seq * 10L));
                UUID eventId = UUID.nameUUIDFromBytes((accountId + "-seq-" + seq).getBytes(StandardCharsets.UTF_8));
                UUID cId = UUID.nameUUIDFromBytes((accountId + "-corr-" + seq).getBytes(StandardCharsets.UTF_8));
                UUID caId = UUID.nameUUIDFromBytes((accountId + "-caus-" + seq).getBytes(StandardCharsets.UTF_8));
                EventMetadata meta = new EventMetadata(cId, caId, "bench-actor", "bench-multi-idemp-" + seq);

                ObjectNode depPayload = MAPPER.createObjectNode()
                    .put("amountMinor", 500L)
                    .put("currency", "INR")
                    .put("reference", "BENCH-MULTI-DEP-" + seq)
                    .put("source", "BENCHMARK")
                    .put("recordedAt", t.toString());

                events.add(new DomainEventEnvelope(
                    eventId,
                    accountId,
                    "Account",
                    (long) seq,
                    "MoneyDeposited",
                    2,
                    t,
                    meta,
                    depPayload
                ));
            }
        }

        return events;
    }

    public static UUID getMultiAggregateId(int index) {
        return UUID.nameUUIDFromBytes(("chronos-bench-multi-account-" + index).getBytes(StandardCharsets.UTF_8));
    }
}
