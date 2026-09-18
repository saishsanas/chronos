package com.chronos.application.service;

import com.chronos.TestDatabaseHelper;
import com.chronos.api.controller.AccountController;
import com.chronos.api.dto.CreateAccountRequest;
import com.chronos.api.dto.DepositRequest;
import com.chronos.application.port.AccountSummaryProjectionRepository;
import com.chronos.domain.projection.ProjectionRebuildJob;
import com.chronos.domain.projection.RebuildStatus;
import com.chronos.infrastructure.cache.RedisCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ProjectionRebuildServiceIntegrationTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        TestDatabaseHelper.configureProperties(registry);
    }

    @Autowired
    private AccountController accountController;

    @Autowired
    private ProjectionRebuildService rebuildService;

    @Autowired
    private ProjectionVerificationService verificationService;

    @Autowired
    private AccountSummaryProjectionRepository projectionRepository;

    @Autowired
    private RedisCacheService redisCacheService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE command_idempotency, event_store, outbox_events, inbox_events, account_summary_projection, projection_rebuild_jobs CASCADE");
    }

    @Test
    @DisplayName("Should perform full projection rebuild from EventStore and maintain consistency")
    void fullProjectionRebuild() {
        var acc1 = accountController.createAccount(new CreateAccountRequest("USD", 1000L, 500L), null, null, null).getBody().aggregateId();
        var acc2 = accountController.createAccount(new CreateAccountRequest("EUR", 2000L, 1000L), null, null, null).getBody().aggregateId();

        accountController.deposit(acc1, new DepositRequest(5000L, "USD"), null, null, null);
        accountController.deposit(acc2, new DepositRequest(3000L, "EUR"), null, null, null);

        // Wipe account_summary_projection table manually to simulate read model corruption/loss
        jdbcTemplate.execute("TRUNCATE TABLE account_summary_projection");
        assertThat(projectionRepository.findByAccountId(acc1)).isEmpty();

        // Run full rebuild
        ProjectionRebuildJob job = rebuildService.rebuildFull();
        assertThat(job.status()).isEqualTo(RebuildStatus.SUCCEEDED);
        assertThat(job.eventsProcessed()).isEqualTo(4); // 2 Created + 2 Deposited

        // Verify read model is completely restored from EventStore
        var proj1 = projectionRepository.findByAccountId(acc1).orElseThrow();
        assertThat(proj1.balanceMinor()).isEqualTo(5000L);
        assertThat(proj1.currency()).isEqualTo("USD");

        var proj2 = projectionRepository.findByAccountId(acc2).orElseThrow();
        assertThat(proj2.balanceMinor()).isEqualTo(3000L);
        assertThat(proj2.currency()).isEqualTo("EUR");

        // Verify all projections match EventStore state via ProjectionVerificationService
        var report = verificationService.verifyAll();
        assertThat(report.isConsistent()).isTrue();
        assertThat(report.mismatchCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Should perform targeted aggregate rebuild without affecting other projections")
    void targetedProjectionRebuild() {
        var acc1 = accountController.createAccount(new CreateAccountRequest("USD", 1000L, 500L), null, null, null).getBody().aggregateId();
        var acc2 = accountController.createAccount(new CreateAccountRequest("EUR", 2000L, 1000L), null, null, null).getBody().aggregateId();

        accountController.deposit(acc1, new DepositRequest(10000L, "USD"), null, null, null);

        // Populate initial projection state for both aggregates
        rebuildService.rebuildFull();

        // Wipe acc1 projection manually
        projectionRepository.deleteByAccountId(acc1);
        assertThat(projectionRepository.findByAccountId(acc1)).isEmpty();
        assertThat(projectionRepository.findByAccountId(acc2)).isPresent();

        // Run targeted rebuild for acc1
        ProjectionRebuildJob job = rebuildService.rebuildTargeted(acc1);
        assertThat(job.status()).isEqualTo(RebuildStatus.SUCCEEDED);

        var proj1 = projectionRepository.findByAccountId(acc1).orElseThrow();
        assertThat(proj1.balanceMinor()).isEqualTo(10000L);
    }

    @Test
    @DisplayName("Repeated rebuilds produce exact identical correct projection state")
    void repeatableRebuilds() {
        var acc = accountController.createAccount(new CreateAccountRequest("USD", 1000L, 500L), null, null, null).getBody().aggregateId();
        accountController.deposit(acc, new DepositRequest(4000L, "USD"), null, null, null);

        rebuildService.rebuildFull();
        var proj1 = projectionRepository.findByAccountId(acc).orElseThrow();

        rebuildService.rebuildFull();
        var proj2 = projectionRepository.findByAccountId(acc).orElseThrow();

        assertThat(proj1.balanceMinor()).isEqualTo(proj2.balanceMinor());
        assertThat(proj1.sequenceNumber()).isEqualTo(proj2.sequenceNumber());
    }
}
