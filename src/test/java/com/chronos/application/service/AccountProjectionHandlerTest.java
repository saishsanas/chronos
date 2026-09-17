package com.chronos.application.service;

import com.chronos.application.port.AccountSummaryProjectionRepository;
import com.chronos.domain.account.AccountStatus;
import com.chronos.domain.event.DomainEventEnvelope;
import com.chronos.domain.event.EventMetadata;
import com.chronos.domain.projection.AccountSummaryProjection;
import com.chronos.infrastructure.cache.RedisCacheService;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class AccountProjectionHandlerTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        com.chronos.TestDatabaseHelper.configureProperties(registry);
    }

    @Autowired
    private AccountProjectionHandler projectionHandler;

    @Autowired
    private AccountSummaryProjectionRepository projectionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("TRUNCATE TABLE account_summary_projection");
    }

    private DomainEventEnvelope createEnvelope(UUID aggregateId, long seq, String eventType, Object payloadObj) {
        return new DomainEventEnvelope(
            UUID.randomUUID(), aggregateId, "Account", seq, eventType, 1, Instant.now(),
            new EventMetadata(UUID.randomUUID(), UUID.randomUUID(), "actor-test", "idemp-test"),
            objectMapper.valueToTree(payloadObj)
        );
    }

    @Test
    @DisplayName("1. AccountCreated creates initial projection entry")
    void testAccountCreatedProjection() {
        UUID aggregateId = UUID.randomUUID();
        DomainEventEnvelope envelope = createEnvelope(aggregateId, 1L, "AccountCreated",
            objectMapper.createObjectNode()
                .put("currency", "INR")
                .put("initialOverdraftLimitMinor", 10000L)
                .put("initialTransactionLimitMinor", 50000L)
        );

        projectionHandler.onEvent(envelope);

        Optional<AccountSummaryProjection> proj = projectionRepository.findByAccountId(aggregateId);
        assertThat(proj).isPresent();
        assertThat(proj.get().accountId()).isEqualTo(aggregateId);
        assertThat(proj.get().currency()).isEqualTo("INR");
        assertThat(proj.get().balanceMinor()).isEqualTo(0L);
        assertThat(proj.get().overdraftLimitMinor()).isEqualTo(10000L);
        assertThat(proj.get().transactionLimitMinor()).isEqualTo(50000L);
        assertThat(proj.get().status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(proj.get().sequenceNumber()).isEqualTo(1L);
    }

    @Test
    @DisplayName("2 & 3. MoneyDeposited and MoneyWithdrawn update projection balance")
    void testDepositsAndWithdrawalsProjection() {
        UUID aggregateId = UUID.randomUUID();

        // 1. AccountCreated
        projectionHandler.onEvent(createEnvelope(aggregateId, 1L, "AccountCreated",
            objectMapper.createObjectNode().put("currency", "INR").put("initialOverdraftLimitMinor", 10000L).put("initialTransactionLimitMinor", 50000L)));

        // 2. Deposit 15000
        projectionHandler.onEvent(createEnvelope(aggregateId, 2L, "MoneyDeposited",
            objectMapper.createObjectNode().put("amountMinor", 15000L).put("currency", "INR").put("resultingBalanceMinor", 15000L)));

        AccountSummaryProjection proj2 = projectionRepository.findByAccountId(aggregateId).orElseThrow();
        assertThat(proj2.balanceMinor()).isEqualTo(15000L);
        assertThat(proj2.sequenceNumber()).isEqualTo(2L);

        // 3. Withdraw 5000
        projectionHandler.onEvent(createEnvelope(aggregateId, 3L, "MoneyWithdrawn",
            objectMapper.createObjectNode().put("amountMinor", 5000L).put("currency", "INR").put("resultingBalanceMinor", 10000L)));

        AccountSummaryProjection proj3 = projectionRepository.findByAccountId(aggregateId).orElseThrow();
        assertThat(proj3.balanceMinor()).isEqualTo(10000L);
        assertThat(proj3.sequenceNumber()).isEqualTo(3L);
    }

    @Test
    @DisplayName("4 & 5. Freeze and Unfreeze update status in projection")
    void testFreezeUnfreezeProjection() {
        UUID aggregateId = UUID.randomUUID();

        projectionHandler.onEvent(createEnvelope(aggregateId, 1L, "AccountCreated",
            objectMapper.createObjectNode().put("currency", "INR").put("initialOverdraftLimitMinor", 10000L).put("initialTransactionLimitMinor", 50000L)));

        // Freeze
        projectionHandler.onEvent(createEnvelope(aggregateId, 2L, "AccountFrozen",
            objectMapper.createObjectNode().put("reason", "Suspicious activity")));

        assertThat(projectionRepository.findByAccountId(aggregateId).orElseThrow().status()).isEqualTo(AccountStatus.FROZEN);

        // Unfreeze
        projectionHandler.onEvent(createEnvelope(aggregateId, 3L, "AccountUnfrozen",
            objectMapper.createObjectNode().put("reason", "Audit cleared")));

        assertThat(projectionRepository.findByAccountId(aggregateId).orElseThrow().status()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    @DisplayName("6 & 7. Limit changes update overdraft and transaction limits in projection")
    void testLimitChangesProjection() {
        UUID aggregateId = UUID.randomUUID();

        projectionHandler.onEvent(createEnvelope(aggregateId, 1L, "AccountCreated",
            objectMapper.createObjectNode().put("currency", "INR").put("initialOverdraftLimitMinor", 10000L).put("initialTransactionLimitMinor", 50000L)));

        // Overdraft limit change
        projectionHandler.onEvent(createEnvelope(aggregateId, 2L, "OverdraftLimitChanged",
            objectMapper.createObjectNode().put("newOverdraftLimitMinor", 20000L)));

        assertThat(projectionRepository.findByAccountId(aggregateId).orElseThrow().overdraftLimitMinor()).isEqualTo(20000L);

        // Transaction limit change
        projectionHandler.onEvent(createEnvelope(aggregateId, 3L, "TransactionLimitChanged",
            objectMapper.createObjectNode().put("newTransactionLimitMinor", 100000L)));

        assertThat(projectionRepository.findByAccountId(aggregateId).orElseThrow().transactionLimitMinor()).isEqualTo(100000L);
    }

    @Test
    @DisplayName("8 & 9. CorrectionIssued and AccountClosed update projection state")
    void testCorrectionAndCloseProjection() {
        UUID aggregateId = UUID.randomUUID();

        projectionHandler.onEvent(createEnvelope(aggregateId, 1L, "AccountCreated",
            objectMapper.createObjectNode().put("currency", "INR").put("initialOverdraftLimitMinor", 10000L).put("initialTransactionLimitMinor", 50000L)));

        projectionHandler.onEvent(createEnvelope(aggregateId, 2L, "MoneyDeposited",
            objectMapper.createObjectNode().put("amountMinor", 10000L).put("currency", "INR").put("resultingBalanceMinor", 10000L)));

        // Correction
        projectionHandler.onEvent(createEnvelope(aggregateId, 3L, "CorrectionIssued",
            objectMapper.createObjectNode().put("targetEventId", UUID.randomUUID().toString()).put("correctionType", "REVERSAL").put("direction", "DEBIT").put("adjustmentAmountMinor", 10000L).put("reason", "Refund")));

        assertThat(projectionRepository.findByAccountId(aggregateId).orElseThrow().balanceMinor()).isEqualTo(0L);

        // Close
        projectionHandler.onEvent(createEnvelope(aggregateId, 4L, "AccountClosed",
            objectMapper.createObjectNode().put("reason", "Customer request")));

        assertThat(projectionRepository.findByAccountId(aggregateId).orElseThrow().status()).isEqualTo(AccountStatus.CLOSED);
    }
}
