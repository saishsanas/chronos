package com.chronos.infrastructure.persistence.postgres;

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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
public class PostgresInboxRepositoryTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        com.chronos.TestDatabaseHelper.configureProperties(registry);
    }

    @Autowired
    private PostgresInboxRepository inboxRepository;

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
    @DisplayName("Should save and find inbox event record by eventId")
    void testSaveAndFindByEventId() {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        Instant now = Instant.now();

        DomainEventEnvelope envelope = new DomainEventEnvelope(
            eventId, aggregateId, "Account", 1L, "AccountCreated", 1, now,
            new EventMetadata(UUID.randomUUID(), UUID.randomUUID(), "actor-1", "idemp-1"),
            objectMapper.createObjectNode().put("currency", "INR")
        );

        InboxEventRecord record = InboxEventRecord.fromEnvelope(envelope);
        inboxRepository.save(record);

        Optional<InboxEventRecord> found = inboxRepository.findByEventId(eventId);
        assertThat(found).isPresent();
        assertThat(found.get().eventId()).isEqualTo(eventId);
        assertThat(found.get().aggregateId()).isEqualTo(aggregateId);
        assertThat(found.get().status()).isEqualTo(InboxStatus.RECEIVED);
    }

    @Test
    @DisplayName("Should enforce unique event_id constraint in inbox_events")
    void testUniqueEventIdConstraint() {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        Instant now = Instant.now();

        DomainEventEnvelope envelope = new DomainEventEnvelope(
            eventId, aggregateId, "Account", 1L, "AccountCreated", 1, now,
            new EventMetadata(UUID.randomUUID(), UUID.randomUUID(), "actor-1", "idemp-1"),
            objectMapper.createObjectNode().put("currency", "INR")
        );

        InboxEventRecord record1 = InboxEventRecord.fromEnvelope(envelope);
        inboxRepository.save(record1);

        InboxEventRecord record2 = InboxEventRecord.fromEnvelope(envelope);
        assertThatThrownBy(() -> inboxRepository.save(record2))
            .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("Should mark inbox event PROCESSED and track consumer sequence state")
    void testMarkProcessedAndUpdateSequence() {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        Instant now = Instant.now();

        DomainEventEnvelope envelope = new DomainEventEnvelope(
            eventId, aggregateId, "Account", 1L, "AccountCreated", 1, now,
            new EventMetadata(UUID.randomUUID(), UUID.randomUUID(), "actor-1", "idemp-1"),
            objectMapper.createObjectNode().put("currency", "INR")
        );

        InboxEventRecord record = InboxEventRecord.fromEnvelope(envelope);
        inboxRepository.save(record);

        inboxRepository.markProcessed(record.inboxId(), now);
        inboxRepository.updateConsumerSequence(aggregateId, 1L, now);

        Optional<InboxEventRecord> found = inboxRepository.findByEventId(eventId);
        assertThat(found).isPresent();
        assertThat(found.get().status()).isEqualTo(InboxStatus.PROCESSED);
        assertThat(found.get().processedAt()).isNotNull();

        long lastSeq = inboxRepository.getLastProcessedSequence(aggregateId);
        assertThat(lastSeq).isEqualTo(1L);
    }
}
