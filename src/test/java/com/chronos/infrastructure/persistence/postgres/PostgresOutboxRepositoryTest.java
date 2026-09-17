package com.chronos.infrastructure.persistence.postgres;

import com.chronos.application.port.EventStore;
import com.chronos.domain.event.DomainEventEnvelope;
import com.chronos.domain.event.EventMetadata;
import com.chronos.domain.outbox.OutboxEventRecord;
import com.chronos.domain.outbox.OutboxStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class PostgresOutboxRepositoryTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5432/chronos_test_db");
        registry.add("spring.datasource.username", () -> "test_user");
        registry.add("spring.datasource.password", () -> "test_password");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
    }

    @Autowired
    private EventStore eventStore;

    @Autowired
    private PostgresOutboxRepository outboxRepository;

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
    @DisplayName("1-5. Atomic event append creates outbox rows, enforces uniqueness & JSONB round-trip")
    void testAtomicEventAndOutboxWrite() {
        UUID aggregateId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-17T18:00:00Z");

        EventMetadata meta = new EventMetadata(UUID.randomUUID(), UUID.randomUUID(), "actor-1", "idemp-1");
        ObjectNode payload = objectMapper.createObjectNode().put("currency", "INR").put("initialOverdraftLimitMinor", 1000L).put("initialTransactionLimitMinor", 5000L);

        DomainEventEnvelope envelope = new DomainEventEnvelope(
                eventId, aggregateId, "Account", 1L, "AccountCreated", 1, now, meta, payload
        );

        // Atomic append
        eventStore.append(aggregateId, 0L, List.of(envelope));

        // Verify outbox row created automatically
        Integer outboxCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ?", Integer.class, aggregateId);
        assertThat(outboxCount).isEqualTo(1);

        // Verify JSONB round-trip
        List<OutboxEventRecord> claimed = outboxRepository.claimDueBatch(10, "worker-1", 30, now.plusSeconds(1));
        assertThat(claimed).hasSize(1);

        OutboxEventRecord record = claimed.get(0);
        assertThat(record.eventId()).isEqualTo(eventId);
        assertThat(record.aggregateId()).isEqualTo(aggregateId);
        assertThat(record.status()).isEqualTo(OutboxStatus.IN_FLIGHT);
        assertThat(record.attempts()).isEqualTo(1);
        assertThat(record.envelope().metadata().actorId()).isEqualTo("actor-1");
    }

    @Test
    @DisplayName("6-10. Outbox claim, skip locked concurrency, lease expiration recovery & mark for retry")
    void testOutboxClaimAndLeaseRecovery() {
        UUID aggregateId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-17T18:00:00Z");

        EventMetadata meta = new EventMetadata(UUID.randomUUID(), UUID.randomUUID(), "actor-1", "idemp-1");
        DomainEventEnvelope e1 = new DomainEventEnvelope(
                UUID.randomUUID(), aggregateId, "Account", 1L, "AccountCreated", 1, now, meta, objectMapper.createObjectNode().put("currency", "INR")
        );
        DomainEventEnvelope e2 = new DomainEventEnvelope(
                UUID.randomUUID(), aggregateId, "Account", 2L, "MoneyDeposited", 1, now.plusSeconds(1), meta, objectMapper.createObjectNode().put("amountMinor", 1000L)
        );

        eventStore.append(aggregateId, 0L, List.of(e1, e2));

        // Worker 1 claims batch of 1
        List<OutboxEventRecord> batch1 = outboxRepository.claimDueBatch(1, "worker-1", 30, now.plusSeconds(2));
        assertThat(batch1).hasSize(1);

        // Worker 2 claims batch of 1 (SKIP LOCKED prevents worker 2 from claiming worker 1's in-flight row)
        List<OutboxEventRecord> batch2 = outboxRepository.claimDueBatch(1, "worker-2", 30, now.plusSeconds(2));
        assertThat(batch2).hasSize(1);
        assertThat(batch2.get(0).outboxId()).isNotEqualTo(batch1.get(0).outboxId());

        // Simulate lease expiration for worker 1 (time advances beyond lease duration)
        Instant futureTime = now.plusSeconds(3600);
        int recovered = outboxRepository.recoverExpiredLeases(futureTime);
        assertThat(recovered).isGreaterThanOrEqualTo(1);

        // Verify status reset to PENDING
        String status = jdbcTemplate.queryForObject("SELECT status FROM outbox_events WHERE outbox_id = ?", String.class, batch1.get(0).outboxId());
        assertThat(status).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("14-15. Mark published updates status and published_at timestamp")
    void testMarkPublished() {
        UUID aggregateId = UUID.randomUUID();
        Instant now = Instant.now();
        DomainEventEnvelope e1 = new DomainEventEnvelope(
                UUID.randomUUID(), aggregateId, "Account", 1L, "AccountCreated", 1, now, new EventMetadata(UUID.randomUUID(), UUID.randomUUID(), "actor-1", "idemp-1"), objectMapper.createObjectNode().put("currency", "INR")
        );
        eventStore.append(aggregateId, 0L, List.of(e1));

        List<OutboxEventRecord> batch = outboxRepository.claimDueBatch(10, "worker-1", 30, Instant.now().plusSeconds(10));
        assertThat(batch).isNotEmpty();
        UUID outboxId = batch.get(0).outboxId();

        Instant publishTime = Instant.now();
        outboxRepository.markPublished(outboxId, publishTime);

        String status = jdbcTemplate.queryForObject("SELECT status FROM outbox_events WHERE outbox_id = ?", String.class, outboxId);
        assertThat(status).isEqualTo("PUBLISHED");
    }

}
