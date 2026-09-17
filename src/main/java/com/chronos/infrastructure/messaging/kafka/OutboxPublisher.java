package com.chronos.infrastructure.messaging.kafka;

import com.chronos.application.port.OutboxRepository;
import com.chronos.domain.event.DomainEventEnvelope;
import com.chronos.domain.outbox.OutboxEventRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Service
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final OutboxPublisherConfig config;

    public OutboxPublisher(
            OutboxRepository outboxRepository,
            @Autowired(required = false) KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            OutboxPublisherConfig config
    ) {
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository must not be null");
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    public int processOutboxBatch() {
        Instant now = Instant.now();

        // 1. Recover expired leases
        int recovered = outboxRepository.recoverExpiredLeases(now);
        if (recovered > 0) {
            log.info("Recovered {} expired outbox leases", recovered);
        }

        // 2. Claim due PENDING / expired IN_FLIGHT batch
        List<OutboxEventRecord> batch = outboxRepository.claimDueBatch(
                config.getBatchSize(),
                config.getWorkerId(),
                config.getLeaseSeconds(),
                now
        );

        if (batch.isEmpty()) {
            return 0;
        }

        log.info("Worker {} claimed {} outbox events for publication", config.getWorkerId(), batch.size());

        int publishedCount = 0;
        for (OutboxEventRecord record : batch) {
            boolean success = publishRecord(record);
            if (success) {
                publishedCount++;
            }
        }

        return publishedCount;
    }

    private boolean publishRecord(OutboxEventRecord record) {
        if (kafkaTemplate == null) {
            log.warn("KafkaTemplate not configured. Scheduling retry for outbox_id {}", record.outboxId());
            long backoff = config.calculateBackoffSeconds(record.attempts());
            Instant nextAttemptAt = Instant.now().plusSeconds(backoff);
            outboxRepository.markForRetry(record.outboxId(), record.attempts(), nextAttemptAt, "KafkaTemplate not configured");
            return false;
        }


        try {
            DomainEventEnvelope envelope = record.envelope();
            String key = record.aggregateId().toString();
            String value = objectMapper.writeValueAsString(envelope);

            ProducerRecord<String, String> producerRecord = new ProducerRecord<>(config.getTopic(), key, value);

            // Add standard headers
            producerRecord.headers().add(new RecordHeader("eventId", record.eventId().toString().getBytes(StandardCharsets.UTF_8)));
            producerRecord.headers().add(new RecordHeader("eventType", record.eventType().getBytes(StandardCharsets.UTF_8)));
            producerRecord.headers().add(new RecordHeader("eventVersion", String.valueOf(record.eventVersion()).getBytes(StandardCharsets.UTF_8)));
            producerRecord.headers().add(new RecordHeader("aggregateId", record.aggregateId().toString().getBytes(StandardCharsets.UTF_8)));

            if (envelope.metadata().correlationId() != null) {
                producerRecord.headers().add(new RecordHeader("correlationId", envelope.metadata().correlationId().toString().getBytes(StandardCharsets.UTF_8)));
            }
            if (envelope.metadata().causationId() != null) {
                producerRecord.headers().add(new RecordHeader("causationId", envelope.metadata().causationId().toString().getBytes(StandardCharsets.UTF_8)));
            }

            // Blocking send to Kafka topic with ack
            kafkaTemplate.send(producerRecord).get();

            Instant publishedAt = Instant.now();
            outboxRepository.markPublished(record.outboxId(), publishedAt);
            log.info("Successfully published outbox_id {} (eventId: {}, sequence: {}) to topic {}",
                    record.outboxId(), record.eventId(), record.sequenceNumber(), config.getTopic());
            return true;

        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            long backoff = config.calculateBackoffSeconds(record.attempts());
            Instant nextAttemptAt = Instant.now().plusSeconds(backoff);

            outboxRepository.markForRetry(record.outboxId(), record.attempts(), nextAttemptAt, errorMsg);
            log.error("Failed to publish outbox_id {} (eventId: {}). Scheduled retry in {}s: {}",
                    record.outboxId(), record.eventId(), backoff, errorMsg);
            return false;
        }
    }
}
