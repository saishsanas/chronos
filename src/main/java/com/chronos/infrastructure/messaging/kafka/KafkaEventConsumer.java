package com.chronos.infrastructure.messaging.kafka;

import com.chronos.application.service.InboxEventProcessor;
import com.chronos.domain.event.DomainEventEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class KafkaEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventConsumer.class);

    private final InboxEventProcessor inboxEventProcessor;
    private final ObjectMapper objectMapper;

    public KafkaEventConsumer(InboxEventProcessor inboxEventProcessor, ObjectMapper objectMapper) {
        this.inboxEventProcessor = Objects.requireNonNull(inboxEventProcessor, "inboxEventProcessor must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @KafkaListener(
        topics = "${chronos.kafka.topic:chronos.events.v1}",
        groupId = "${chronos.kafka.consumer.group-id:chronos-engine-v1}",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void listen(ConsumerRecord<String, String> record, Acknowledgment ack) {
        log.info("Received Kafka record key={}, topic={}, partition={}, offset={}",
            record.key(), record.topic(), record.partition(), record.offset());

        try {
            DomainEventEnvelope envelope = objectMapper.readValue(record.value(), DomainEventEnvelope.class);
            InboxEventProcessor.ProcessResult result = inboxEventProcessor.process(envelope);
            log.info("Inbox processing result for eventId {}: {}", envelope.eventId(), result);

            if (result == InboxEventProcessor.ProcessResult.QUARANTINED) {
                log.warn("Kafka event key={} (eventId={}) was QUARANTINED due to poison payload. Acknowledging offset to unblock consumer pipeline.",
                    record.key(), envelope.eventId());
            }

            // Acknowledge Kafka message after successful processing, confirmed duplicate, or poison event quarantine
            if (ack != null) {
                ack.acknowledge();
            }

        } catch (Exception e) {
            log.error("Failed to process Kafka record key={}: {}", record.key(), e.getMessage());
            // Do NOT acknowledge on unhandled exception; allow Kafka retry container to handle redelivery
            throw new RuntimeException("Kafka event processing failed for record key=" + record.key(), e);
        }
    }
}
