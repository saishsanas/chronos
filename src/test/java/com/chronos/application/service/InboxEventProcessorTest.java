package com.chronos.application.service;

import com.chronos.application.port.ConsumerEventIntegrityException;
import com.chronos.application.port.DownstreamEventConsumer;
import com.chronos.application.port.InboxRepository;
import com.chronos.application.port.InvalidEventEnvelopeException;
import com.chronos.domain.event.DomainEventEnvelope;
import com.chronos.domain.event.EventMetadata;
import com.chronos.domain.inbox.InboxEventRecord;
import com.chronos.domain.inbox.InboxStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
public class InboxEventProcessorTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        com.chronos.TestDatabaseHelper.configureProperties(registry);
    }

    @Autowired
    private InboxRepository inboxRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private TestDownstreamConsumer testConsumer;
    private InboxEventProcessor processor;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("TRUNCATE TABLE inbox_events");
        jdbcTemplate.update("TRUNCATE TABLE consumer_aggregate_state");

        testConsumer = new TestDownstreamConsumer();
        processor = new InboxEventProcessor(inboxRepository, testConsumer);
    }

    private DomainEventEnvelope createEnvelope(UUID aggregateId, long seq, UUID eventId, String eventType) {
        return new DomainEventEnvelope(
            eventId != null ? eventId : UUID.randomUUID(),
            aggregateId,
            "Account",
            seq,
            eventType != null ? eventType : "AccountCreated",
            1,
            Instant.now(),
            new EventMetadata(UUID.randomUUID(), UUID.randomUUID(), "actor-test", "idemp-test"),
            objectMapper.createObjectNode().put("currency", "INR")
        );
    }

    @Test
    @DisplayName("1. New valid event is processed once and marked PROCESSED")
    void testNewEventProcessedOnce() {
        UUID aggregateId = UUID.randomUUID();
        DomainEventEnvelope envelope = createEnvelope(aggregateId, 1L, null, "AccountCreated");

        InboxEventProcessor.ProcessResult result = processor.process(envelope);

        assertThat(result).isEqualTo(InboxEventProcessor.ProcessResult.PROCESSED);
        assertThat(testConsumer.consumedEnvelopes).hasSize(1);
        assertThat(testConsumer.consumedEnvelopes.get(0).eventId()).isEqualTo(envelope.eventId());

        Optional<InboxEventRecord> record = inboxRepository.findByEventId(envelope.eventId());
        assertThat(record).isPresent();
        assertThat(record.get().status()).isEqualTo(InboxStatus.PROCESSED);
        assertThat(inboxRepository.getLastProcessedSequence(aggregateId)).isEqualTo(1L);
    }

    @Test
    @DisplayName("2 & 3. Same eventId delivered twice causes NO second business effect")
    void testDuplicateEventIdDeliveredTwice() {
        UUID aggregateId = UUID.randomUUID();
        DomainEventEnvelope envelope = createEnvelope(aggregateId, 1L, null, "AccountCreated");

        // First delivery
        InboxEventProcessor.ProcessResult result1 = processor.process(envelope);
        assertThat(result1).isEqualTo(InboxEventProcessor.ProcessResult.PROCESSED);
        assertThat(testConsumer.consumedEnvelopes).hasSize(1);

        // Second delivery (duplicate)
        InboxEventProcessor.ProcessResult result2 = processor.process(envelope);
        assertThat(result2).isEqualTo(InboxEventProcessor.ProcessResult.DUPLICATE);

        // Downstream effect MUST remain 1 (no second execution)
        assertThat(testConsumer.consumedEnvelopes).hasSize(1);
    }

    @Test
    @DisplayName("4. Sequence continuity (seq 1 -> 2 -> 3) is processed successfully")
    void testSequenceContinuity() {
        UUID aggregateId = UUID.randomUUID();

        processor.process(createEnvelope(aggregateId, 1L, null, "AccountCreated"));
        processor.process(createEnvelope(aggregateId, 2L, null, "MoneyDeposited"));
        processor.process(createEnvelope(aggregateId, 3L, null, "MoneyWithdrawn"));

        assertThat(testConsumer.consumedEnvelopes).hasSize(3);
        assertThat(inboxRepository.getLastProcessedSequence(aggregateId)).isEqualTo(3L);
    }

    @Test
    @DisplayName("5. Initial sequence for new aggregate starting at > 1 is rejected as gap")
    void testInitialSequenceGapRejected() {
        UUID aggregateId = UUID.randomUUID();
        DomainEventEnvelope envelope = createEnvelope(aggregateId, 2L, null, "AccountCreated");

        assertThatThrownBy(() -> processor.process(envelope))
            .isInstanceOf(ConsumerEventIntegrityException.class)
            .hasMessageContaining("Initial sequence for aggregate");
    }

    @Test
    @DisplayName("6. Sequence gap (seq 1 -> seq 3) is rejected")
    void testSequenceGapRejected() {
        UUID aggregateId = UUID.randomUUID();
        processor.process(createEnvelope(aggregateId, 1L, null, "AccountCreated"));

        DomainEventEnvelope gapEnvelope = createEnvelope(aggregateId, 3L, null, "MoneyDeposited");
        assertThatThrownBy(() -> processor.process(gapEnvelope))
            .isInstanceOf(ConsumerEventIntegrityException.class)
            .hasMessageContaining("Sequence gap detected");
    }

    @Test
    @DisplayName("7. Sequence regression (seq 2 -> seq 1) is rejected")
    void testSequenceRegressionRejected() {
        UUID aggregateId = UUID.randomUUID();
        processor.process(createEnvelope(aggregateId, 1L, null, "AccountCreated"));
        processor.process(createEnvelope(aggregateId, 2L, null, "MoneyDeposited"));

        DomainEventEnvelope regressionEnvelope = createEnvelope(aggregateId, 1L, UUID.randomUUID(), "MoneyWithdrawn");
        assertThatThrownBy(() -> processor.process(regressionEnvelope))
            .isInstanceOf(ConsumerEventIntegrityException.class)
            .hasMessageContaining("Sequence regression/collision");
    }

    @Test
    @DisplayName("8. Different eventId with same sequence number is rejected as sequence collision")
    void testDifferentEventIdWithSameSequenceRejected() {
        UUID aggregateId = UUID.randomUUID();
        processor.process(createEnvelope(aggregateId, 1L, null, "AccountCreated"));

        DomainEventEnvelope collisionEnvelope = createEnvelope(aggregateId, 1L, UUID.randomUUID(), "MoneyDeposited");
        assertThatThrownBy(() -> processor.process(collisionEnvelope))
            .isInstanceOf(ConsumerEventIntegrityException.class)
            .hasMessageContaining("Sequence regression/collision");
    }

    @Test
    @DisplayName("9. Unsupported event version is rejected")
    void testUnsupportedEventVersionRejected() {
        UUID aggregateId = UUID.randomUUID();
        DomainEventEnvelope envelope = new DomainEventEnvelope(
            UUID.randomUUID(), aggregateId, "Account", 1L, "AccountCreated", 2, Instant.now(),
            new EventMetadata(UUID.randomUUID(), UUID.randomUUID(), "actor", "idemp"),
            objectMapper.createObjectNode().put("currency", "INR")
        );

        assertThatThrownBy(() -> processor.process(envelope))
            .isInstanceOf(InvalidEventEnvelopeException.class)
            .hasMessageContaining("Unsupported eventVersion");
    }

    @Test
    @DisplayName("10. Unknown event type or malformed envelope is rejected")
    void testUnknownEventTypeRejected() {
        UUID aggregateId = UUID.randomUUID();
        DomainEventEnvelope envelope = createEnvelope(aggregateId, 1L, null, "UnknownFakeType");

        assertThatThrownBy(() -> processor.process(envelope))
            .isInstanceOf(InvalidEventEnvelopeException.class)
            .hasMessageContaining("Unsupported or unknown eventType");
    }

    @Test
    @DisplayName("11 & 12. Downstream processing failure leaves event retryable, retry succeeds")
    void testDownstreamFailureAndRetry() {
        UUID aggregateId = UUID.randomUUID();
        DomainEventEnvelope envelope = createEnvelope(aggregateId, 1L, null, "AccountCreated");

        testConsumer.shouldFailNext = true;

        assertThatThrownBy(() -> processor.process(envelope))
            .isInstanceOf(RuntimeException.class)
            .hasMessage("Simulated downstream processing failure");

        Optional<InboxEventRecord> record = inboxRepository.findByEventId(envelope.eventId());
        assertThat(record).isPresent();
        assertThat(record.get().status()).isEqualTo(InboxStatus.RECEIVED);
        assertThat(record.get().attempts()).isEqualTo(1);
        assertThat(record.get().lastError()).contains("Simulated downstream processing failure");

        // Retry without error
        testConsumer.shouldFailNext = false;
        InboxEventProcessor.ProcessResult result = processor.process(envelope);
        assertThat(result).isEqualTo(InboxEventProcessor.ProcessResult.PROCESSED);

        Optional<InboxEventRecord> updatedRecord = inboxRepository.findByEventId(envelope.eventId());
        assertThat(updatedRecord).isPresent();
        assertThat(updatedRecord.get().status()).isEqualTo(InboxStatus.PROCESSED);
    }

    @Test
    @DisplayName("13. Crash-before-ack duplicate is safely ignored")
    void testCrashBeforeAckDuplicateIgnored() {
        UUID aggregateId = UUID.randomUUID();
        DomainEventEnvelope envelope = createEnvelope(aggregateId, 1L, null, "AccountCreated");

        // Simulate crash right after DB commit (status marked PROCESSED)
        processor.process(envelope);

        // Kafka redelivers envelope
        InboxEventProcessor.ProcessResult result = processor.process(envelope);
        assertThat(result).isEqualTo(InboxEventProcessor.ProcessResult.DUPLICATE);
        assertThat(testConsumer.consumedEnvelopes).hasSize(1);
    }

    @Test
    @DisplayName("14. Aggregate isolation: distinct aggregates track sequences independently")
    void testAggregateIsolation() {
        UUID agg1 = UUID.randomUUID();
        UUID agg2 = UUID.randomUUID();

        processor.process(createEnvelope(agg1, 1L, null, "AccountCreated"));
        processor.process(createEnvelope(agg1, 2L, null, "MoneyDeposited"));

        processor.process(createEnvelope(agg2, 1L, null, "AccountCreated"));

        assertThat(inboxRepository.getLastProcessedSequence(agg1)).isEqualTo(2L);
        assertThat(inboxRepository.getLastProcessedSequence(agg2)).isEqualTo(1L);
    }

    private static class TestDownstreamConsumer implements DownstreamEventConsumer {
        final List<DomainEventEnvelope> consumedEnvelopes = new ArrayList<>();
        boolean shouldFailNext = false;

        @Override
        public void onEvent(DomainEventEnvelope envelope) {
            if (shouldFailNext) {
                throw new RuntimeException("Simulated downstream processing failure");
            }
            consumedEnvelopes.add(envelope);
        }
    }
}
