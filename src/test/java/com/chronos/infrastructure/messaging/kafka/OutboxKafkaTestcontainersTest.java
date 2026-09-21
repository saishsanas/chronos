package com.chronos.infrastructure.messaging.kafka;

import com.chronos.application.port.EventStore;
import com.chronos.application.port.OutboxRepository;
import com.chronos.domain.event.DomainEventEnvelope;
import com.chronos.domain.event.EventMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class OutboxKafkaTestcontainersTest {

    private static ConfluentKafkaContainer kafkaContainer;

    @BeforeAll
    static void checkDockerEnvironment() {
        boolean dockerAvailable = false;
        try {
            dockerAvailable = DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            dockerAvailable = false;
        }

        Assumptions.assumeTrue(dockerAvailable, "Docker environment is required for Testcontainers Kafka integration test");

        kafkaContainer = new ConfluentKafkaContainer(
                DockerImageName.parse("confluentinc/cp-kafka:7.6.0")
        );
        kafkaContainer.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        com.chronos.TestDatabaseHelper.configureProperties(registry);
        if (kafkaContainer != null && kafkaContainer.isRunning()) {
            registry.add("spring.kafka.bootstrap-servers", kafkaContainer::getBootstrapServers);
        } else {
            registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9092");
        }
        registry.add("chronos.kafka.topic", () -> "chronos.events.tc.v1");
        registry.add("spring.kafka.producer.acks", () -> "all");
    }

    @Autowired
    private EventStore eventStore;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private OutboxPublisher outboxPublisher;

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
    @DisplayName("Real Kafka Testcontainers verification: outbox event published to real Kafka broker container")
    void testRealKafkaTestcontainersPublication() throws Exception {
        Assumptions.assumeTrue(kafkaContainer != null && kafkaContainer.isRunning(), "Kafka container is not running");

        UUID aggregateId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.now();

        EventMetadata meta = new EventMetadata(UUID.randomUUID(), UUID.randomUUID(), "tc-actor", "tc-idemp-1");
        ObjectNode payload = objectMapper.createObjectNode().put("currency", "INR").put("initialOverdraftLimitMinor", 10000L).put("initialTransactionLimitMinor", 50000L);

        DomainEventEnvelope envelope = new DomainEventEnvelope(
                eventId, aggregateId, "Account", 1L, "AccountCreated", 1, now, meta, payload
        );

        // 1. Atomic append to event_store + outbox_events
        eventStore.append(aggregateId, 0L, List.of(envelope));

        // 2. Setup real consumer to listen on test topic
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "tc-group-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<String, String>(props).createConsumer();
        consumer.subscribe(Collections.singletonList("chronos.events.tc.v1"));

        // 3. Execute OutboxPublisher relay against real Testcontainers Kafka broker
        int publishedCount = outboxPublisher.processOutboxBatch();
        assertThat(publishedCount).isEqualTo(1);

        // 4. Consume from real Kafka broker
        ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(10));
        assertThat(records.count()).isEqualTo(1);

        ConsumerRecord<String, String> record = records.iterator().next();
        assertThat(record.key()).isEqualTo(aggregateId.toString());

        DomainEventEnvelope received = objectMapper.readValue(record.value(), DomainEventEnvelope.class);
        assertThat(received.eventId()).isEqualTo(eventId);
        assertThat(received.aggregateId()).isEqualTo(aggregateId);

        assertThat(new String(record.headers().lastHeader("eventId").value(), StandardCharsets.UTF_8)).isEqualTo(eventId.toString());
        assertThat(new String(record.headers().lastHeader("aggregateId").value(), StandardCharsets.UTF_8)).isEqualTo(aggregateId.toString());

        // 5. Verify database outbox status marked PUBLISHED
        String status = jdbcTemplate.queryForObject("SELECT status FROM outbox_events WHERE event_id = ?", String.class, eventId);
        assertThat(status).isEqualTo("PUBLISHED");

        consumer.close();
    }
}
