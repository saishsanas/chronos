package com.chronos.application.service;

import com.chronos.application.port.AccountSummaryProjectionRepository;
import com.chronos.domain.account.AccountStatus;
import com.chronos.domain.event.DomainEventEnvelope;
import com.chronos.domain.event.EventMetadata;
import com.chronos.domain.projection.AccountSummaryProjection;
import com.chronos.domain.projection.ProjectionRebuildJob;
import com.chronos.domain.projection.RebuildStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class ProjectionSchemaCompatibilityIntegrationTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        com.chronos.TestDatabaseHelper.configureProperties(registry);
    }

    @Autowired
    private AccountProjectionHandler projectionHandler;

    @Autowired
    private ProjectionRebuildService rebuildService;

    @Autowired
    private AccountSummaryProjectionRepository projectionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("TRUNCATE TABLE projection_rebuild_jobs");
        jdbcTemplate.update("TRUNCATE TABLE account_summary_projection");
        jdbcTemplate.update("TRUNCATE TABLE event_store");
    }

    @Test
    @DisplayName("SEMANTIC EQUIVALENCE: v1 deposit and v2 deposit produce equivalent projection state")
    void testSemanticEquivalenceOfV1AndV2Projections() {
        UUID account1 = UUID.randomUUID();
        UUID account2 = UUID.randomUUID();
        Instant now = Instant.now();
        EventMetadata meta = new EventMetadata(UUID.randomUUID(), UUID.randomUUID(), "actor", "key");

        // Initialize Account 1 and Account 2 with AccountCreated v1
        DomainEventEnvelope create1 = new DomainEventEnvelope(
            UUID.randomUUID(), account1, "Account", 1L, "AccountCreated", 1, now, meta,
            objectMapper.createObjectNode().put("currency", "USD").put("initialOverdraftLimitMinor", 0L).put("initialTransactionLimitMinor", 500000L)
        );
        DomainEventEnvelope create2 = new DomainEventEnvelope(
            UUID.randomUUID(), account2, "Account", 1L, "AccountCreated", 1, now, meta,
            objectMapper.createObjectNode().put("currency", "USD").put("initialOverdraftLimitMinor", 0L).put("initialTransactionLimitMinor", 500000L)
        );
        projectionHandler.onEvent(create1);
        projectionHandler.onEvent(create2);

        // Account 1 receives legacy v1 MoneyDeposited (no source)
        DomainEventEnvelope v1Deposit = new DomainEventEnvelope(
            UUID.randomUUID(), account1, "Account", 2L, "MoneyDeposited", 1, now.plusSeconds(1), meta,
            objectMapper.createObjectNode().put("amountMinor", 75000L).put("currency", "USD").put("resultingBalanceMinor", 75000L)
        );
        projectionHandler.onEvent(v1Deposit);

        // Account 2 receives modern v2 MoneyDeposited (with source)
        DomainEventEnvelope v2Deposit = new DomainEventEnvelope(
            UUID.randomUUID(), account2, "Account", 2L, "MoneyDeposited", 2, now.plusSeconds(1), meta,
            objectMapper.createObjectNode().put("amountMinor", 75000L).put("currency", "USD").put("resultingBalanceMinor", 75000L).put("source", "MANUAL")
        );
        projectionHandler.onEvent(v2Deposit);

        // Verify both projections have equivalent balance, status, and sequence
        Optional<AccountSummaryProjection> proj1 = projectionRepository.findByAccountId(account1);
        Optional<AccountSummaryProjection> proj2 = projectionRepository.findByAccountId(account2);

        assertThat(proj1).isPresent();
        assertThat(proj2).isPresent();
        assertThat(proj1.get().balanceMinor()).isEqualTo(75000L);
        assertThat(proj2.get().balanceMinor()).isEqualTo(75000L);
        assertThat(proj1.get().status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(proj2.get().status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(proj1.get().sequenceNumber()).isEqualTo(2L);
        assertThat(proj2.get().sequenceNumber()).isEqualTo(2L);
    }

    @Test
    @DisplayName("PROJECTION REBUILD: Rebuilding projection from mixed v1/v2 event store succeeds")
    void testProjectionRebuildFromMixedVersionStream() {
        UUID accountId = UUID.randomUUID();
        Instant t0 = Instant.now().minus(30, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);

        String sql = """
            INSERT INTO event_store (
                event_id, aggregate_id, aggregate_type, sequence_number,
                event_type, event_version, recorded_at, metadata, payload
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
            """;

        // Seq 1: AccountCreated (v1)
        jdbcTemplate.update(sql, UUID.randomUUID(), accountId, "Account", 1L, "AccountCreated", 1,
            Timestamp.from(t0), "{}", "{\"currency\":\"GBP\",\"initialOverdraftLimitMinor\":0,\"initialTransactionLimitMinor\":500000}");

        // Seq 2: MoneyDeposited (legacy v1)
        jdbcTemplate.update(sql, UUID.randomUUID(), accountId, "Account", 2L, "MoneyDeposited", 1,
            Timestamp.from(t0.plusSeconds(10)), "{}", "{\"amountMinor\":100000,\"currency\":\"GBP\",\"resultingBalanceMinor\":100000}");

        // Seq 3: MoneyDeposited (canonical v2)
        jdbcTemplate.update(sql, UUID.randomUUID(), accountId, "Account", 3L, "MoneyDeposited", 2,
            Timestamp.from(t0.plusSeconds(20)), "{}", "{\"amountMinor\":50000,\"currency\":\"GBP\",\"resultingBalanceMinor\":150000,\"source\":\"ONLINE\"}");

        // Seq 4: MoneyWithdrawn (v1)
        jdbcTemplate.update(sql, UUID.randomUUID(), accountId, "Account", 4L, "MoneyWithdrawn", 1,
            Timestamp.from(t0.plusSeconds(30)), "{}", "{\"amountMinor\":20000,\"currency\":\"GBP\",\"resultingBalanceMinor\":130000}");

        // Execute full projection rebuild
        ProjectionRebuildJob job = rebuildService.rebuildFull();

        assertThat(job.status()).isEqualTo(RebuildStatus.SUCCEEDED);
        assertThat(job.eventsProcessed()).isEqualTo(4L);
        assertThat(job.resultingSequence()).isEqualTo(4L);

        // Verify rebuilt projection in account_summary_projection
        Optional<AccountSummaryProjection> projectionOpt = projectionRepository.findByAccountId(accountId);
        assertThat(projectionOpt).isPresent();
        AccountSummaryProjection proj = projectionOpt.get();
        assertThat(proj.balanceMinor()).isEqualTo(130000L);
        assertThat(proj.sequenceNumber()).isEqualTo(4L);
        assertThat(proj.status()).isEqualTo(AccountStatus.ACTIVE);
    }
}
