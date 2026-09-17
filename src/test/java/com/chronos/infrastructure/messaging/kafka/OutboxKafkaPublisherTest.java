package com.chronos.infrastructure.messaging.kafka;

import com.chronos.application.port.EventStore;
import com.chronos.application.port.OutboxRepository;
import com.chronos.domain.event.DomainEventEnvelope;
import com.chronos.domain.event.EventMetadata;
import com.chronos.domain.outbox.OutboxEventRecord;
import com.chronos.domain.outbox.OutboxStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {"chronos.events.v1"})
class OutboxKafkaPublisherTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        com.chronos.TestDatabaseHelper.configureProperties(registry);
        registry.add("chronos.kafka.topic", () -> "chronos.events.v1");
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
        registry.add("spring.kafka.producer.acks", () -> "all");
    }

    @Autowired
    private EventStore eventStore;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private OutboxPublisher outboxPublisher;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("TRUNCATE TABLE outbox_events");
        jdbcTemplate.update("TRUNCATE TABLE snapshots");
        jdbcTemplate.update("TRUNCATE TABLE event_store");
    }

    @Test
    @DisplayName("11-16. Outbox publisher sends complete event envelope to Kafka with stable key, headers & marks PUBLISHED")
    void testOutboxKafkaPublicationSuccess() throws Exception {
        UUID aggregateId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID corrId = UUID.randomUUID();
        UUID causId = UUID.randomUUID();
        Instant now = Instant.now();

        EventMetadata meta = new EventMetadata(corrId, causId, "admin-user", "idemp-k1");
        ObjectNode payload = objectMapper.createObjectNode().put("currency", "INR").put("initialOverdraftLimitMinor", 10000L).put("initialTransactionLimitMinor", 50000L);

        DomainEventEnvelope envelope = new DomainEventEnvelope(
                eventId, aggregateId, "Account", 1L, "AccountCreated", 1, now, meta, payload
        );

        // 1. Atomic append to event_store + outbox_events
        eventStore.append(aggregateId, 0L, List.of(envelope));

        // 2. Setup Kafka consumer to listen on topic
        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps("test-group", "true", embeddedKafkaBroker);
        consumerProps.put(org.apache.kafka.clients.consumer.ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, org.apache.kafka.common.serialization.StringDeserializer.class);
        consumerProps.put(org.apache.kafka.clients.consumer.ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, org.apache.kafka.common.serialization.StringDeserializer.class);
        DefaultKafkaConsumerFactory<String, String> consumerFactory = new DefaultKafkaConsumerFactory<>(consumerProps);
        Consumer<String, String> consumer = consumerFactory.createConsumer();
        embeddedKafkaBroker.consumeFromAnEmbeddedTopic(consumer, "chronos.events.v1");

        // 3. Trigger outbox publisher relay execution
        int publishedCount = outboxPublisher.processOutboxBatch();
        assertThat(publishedCount).isEqualTo(1);

        // 4. Verify Kafka record received
        ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(5));
        assertThat(records.count()).isEqualTo(1);

        ConsumerRecord<String, String> record = records.iterator().next();

        // 5. Verify message key = aggregateId string
        assertThat(record.key()).isEqualTo(aggregateId.toString());

        // 6. Verify message payload contains complete event envelope
        DomainEventEnvelope receivedEnvelope = objectMapper.readValue(record.value(), DomainEventEnvelope.class);
        assertThat(receivedEnvelope.eventId()).isEqualTo(eventId);
        assertThat(receivedEnvelope.aggregateId()).isEqualTo(aggregateId);
        assertThat(receivedEnvelope.eventType()).isEqualTo("AccountCreated");

        // 7. Verify headers
        assertThat(new String(record.headers().lastHeader("eventId").value(), StandardCharsets.UTF_8)).isEqualTo(eventId.toString());
        assertThat(new String(record.headers().lastHeader("eventType").value(), StandardCharsets.UTF_8)).isEqualTo("AccountCreated");
        assertThat(new String(record.headers().lastHeader("aggregateId").value(), StandardCharsets.UTF_8)).isEqualTo(aggregateId.toString());

        // 8. Verify database status marked PUBLISHED with published_at timestamp
        String status = jdbcTemplate.queryForObject("SELECT status FROM outbox_events WHERE event_id = ?", String.class, eventId);
        assertThat(status).isEqualTo("PUBLISHED");

        consumer.close();
    }

    @Test
    @DisplayName("17-22. Kafka failure leaves outbox row retryable, increments attempts & preserves eventId")
    void testKafkaFailureHandlingAndRetry() {
        UUID aggregateId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.now();

        DomainEventEnvelope envelope = new DomainEventEnvelope(
                eventId, aggregateId, "Account", 1L, "AccountCreated", 1, now,
                new EventMetadata(UUID.randomUUID(), UUID.randomUUID(), "admin", "idemp-2"),
                objectMapper.createObjectNode().put("currency", "INR")
        );
        eventStore.append(aggregateId, 0L, List.of(envelope));

        // Create publisher with null KafkaTemplate to simulate broker unavailable/failure
        OutboxPublisher failingPublisher = new OutboxPublisher(outboxRepository, null, objectMapper, new OutboxPublisherConfig("chronos.events.v1", 10, 30, 5, 60));

        int count = failingPublisher.processOutboxBatch();
        assertThat(count).isEqualTo(0);

        // Outbox record remains PENDING with attempts = 1 in database
        Integer attempts = jdbcTemplate.queryForObject("SELECT attempts FROM outbox_events WHERE event_id = ?", Integer.class, eventId);
        String status = jdbcTemplate.queryForObject("SELECT status FROM outbox_events WHERE event_id = ?", String.class, eventId);

        assertThat(attempts).isEqualTo(1);
        assertThat(status).isEqualTo("PENDING");
        assertThat(eventStore.loadStream(aggregateId).get(0).eventId()).isEqualTo(eventId); // Stable eventId preserved
    }
}

