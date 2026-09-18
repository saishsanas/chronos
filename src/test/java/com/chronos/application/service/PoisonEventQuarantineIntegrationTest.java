package com.chronos.application.service;

import com.chronos.TestDatabaseHelper;
import com.chronos.application.port.InboxRepository;
import com.chronos.application.port.InvalidEventEnvelopeException;
import com.chronos.domain.event.DomainEventEnvelope;
import com.chronos.domain.event.EventMetadata;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class PoisonEventQuarantineIntegrationTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        TestDatabaseHelper.configureProperties(registry);
    }

    @Autowired
    private InboxEventProcessor inboxEventProcessor;

    @Autowired
    private InboxRepository inboxRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE inbox_events, consumer_aggregate_state CASCADE");
    }

    @Test
    @DisplayName("Malformed/poison event transitions inbox record to QUARANTINED status and throws InvalidEventEnvelopeException")
    void poisonEventQuarantine() {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();

        // Create poison envelope with unsupported aggregate type
        DomainEventEnvelope poisonEnvelope = new DomainEventEnvelope(
            eventId, aggregateId, "INVALID_AGGREGATE_TYPE", 1L, "AccountCreated", 1,
            Instant.now(), new EventMetadata(UUID.randomUUID(), UUID.randomUUID(), "system", null), objectMapper.createObjectNode()
        );

        assertThatThrownBy(() -> inboxEventProcessor.process(poisonEnvelope))
            .isInstanceOf(InvalidEventEnvelopeException.class);

        var recordOpt = inboxRepository.findByEventId(eventId);
        assertThat(recordOpt).isPresent();
        assertThat(recordOpt.get().status()).isEqualTo(InboxStatus.QUARANTINED);
        assertThat(recordOpt.get().lastError()).contains("Unsupported aggregateType");
    }
}
