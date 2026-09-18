package com.chronos.persistence.postgres;

import com.chronos.TestDatabaseHelper;
import com.chronos.application.port.OutboxRepository;
import com.chronos.domain.event.DomainEventEnvelope;
import com.chronos.domain.event.EventMetadata;
import com.chronos.domain.outbox.OutboxEventRecord;
import com.chronos.domain.outbox.OutboxStatus;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class OutboxReliabilityIntegrationTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        TestDatabaseHelper.configureProperties(registry);
    }

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE outbox_events CASCADE");
    }

    @Test
    @DisplayName("Expired worker lease can be reclaimed by another worker after locked_until expires")
    void outboxLeaseRecovery() {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();

        DomainEventEnvelope envelope = new DomainEventEnvelope(
            eventId, aggregateId, "Account", 1L, "AccountCreated", 1,
            Instant.now(), new EventMetadata(UUID.randomUUID(), UUID.randomUUID(), "system", null), objectMapper.createObjectNode()
        );

        // Save raw outbox record
        jdbcTemplate.update(
            """
            INSERT INTO outbox_events (
                outbox_id, event_id, aggregate_id, aggregate_type, sequence_number,
                event_type, event_version, recorded_at, event_envelope, status,
                attempts, next_attempt_at, locked_until, locked_by, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, 'IN_FLIGHT', 1, ?, ?, 'worker-1', ?)
            """,
            UUID.randomUUID(), eventId, aggregateId, "Account", 1L,
            "AccountCreated", 1, java.sql.Timestamp.from(Instant.now()),
            objectMapper.valueToTree(envelope).toString(),
            java.sql.Timestamp.from(Instant.now()),
            java.sql.Timestamp.from(Instant.now().minusSeconds(60)), // Expired lease in past!
            java.sql.Timestamp.from(Instant.now())
        );

        // Worker 2 attempts claim via claimDueBatch
        List<OutboxEventRecord> claimable = outboxRepository.claimDueBatch(10, "worker-2", 30, Instant.now());
        assertThat(claimable).hasSize(1);
        assertThat(claimable.get(0).eventId()).isEqualTo(eventId);
        assertThat(claimable.get(0).status()).isEqualTo(OutboxStatus.IN_FLIGHT);

        // Worker 2 marks published
        outboxRepository.markPublished(claimable.get(0).outboxId(), Instant.now());

        String status = jdbcTemplate.queryForObject(
            "SELECT status FROM outbox_events WHERE event_id = ?",
            String.class,
            eventId
        );
        assertThat(status).isEqualTo("PUBLISHED");
    }
}
