package com.chronos.infrastructure.messaging.kafka;

import com.chronos.application.port.InboxRepository;
import com.chronos.domain.event.DomainEventEnvelope;
import com.chronos.domain.event.EventMetadata;
import com.chronos.domain.inbox.InboxEventRecord;
import com.chronos.domain.inbox.InboxStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = { "chronos.events.inbox.v1" })
public class KafkaEventConsumerIntegrationTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5432/chronos_test_db");
        registry.add("spring.datasource.username", () -> "test_user");
        registry.add("spring.datasource.password", () -> "test_password");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("chronos.kafka.topic", () -> "chronos.events.inbox.v1");
        registry.add("chronos.kafka.consumer.group-id", () -> "chronos-engine-test-group-" + UUID.randomUUID());
    }

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private InboxRepository inboxRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("TRUNCATE TABLE inbox_events");
        jdbcTemplate.update("TRUNCATE TABLE consumer_aggregate_state");
    }

    @Test
    @DisplayName("End-to-end Embedded Kafka consumer test: event received, processed, and deduplicated")
    void testEndToEndKafkaConsumerDeduplication() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        Instant now = Instant.now();

        DomainEventEnvelope envelope = new DomainEventEnvelope(
            eventId, aggregateId, "Account", 1L, "AccountCreated", 1, now,
            new EventMetadata(UUID.randomUUID(), UUID.randomUUID(), "actor-kafka", "idemp-kafka"),
            objectMapper.createObjectNode().put("currency", "INR")
        );

        String payloadJson = objectMapper.writeValueAsString(envelope);
        ProducerRecord<String, String> record = new ProducerRecord<>("chronos.events.inbox.v1", aggregateId.toString(), payloadJson);

        // 1. Send first message
        kafkaTemplate.send(record).get();

        // 2. Await asynchronous processing by KafkaEventConsumer
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Optional<InboxEventRecord> inboxRecord = inboxRepository.findByEventId(eventId);
            assertThat(inboxRecord).isPresent();
            assertThat(inboxRecord.get().status()).isEqualTo(InboxStatus.PROCESSED);
        });

        // 3. Send duplicate message with same eventId
        kafkaTemplate.send(record).get();

        // 4. Verify no duplicate row created and sequence state remains 1
        await().pollDelay(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(inboxRepository.getLastProcessedSequence(aggregateId)).isEqualTo(1L);
        });
    }
}
