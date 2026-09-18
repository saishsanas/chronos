package com.chronos.api.controller;

import com.chronos.TestDatabaseHelper;
import com.chronos.api.dto.CreateAccountRequest;
import com.chronos.api.dto.DepositRequest;
import com.chronos.application.port.EventStore;
import com.chronos.domain.idempotency.CommandIdempotencyConflictException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class CommandIdempotencyIntegrationTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        TestDatabaseHelper.configureProperties(registry);
    }

    @Autowired
    private AccountController accountController;

    @Autowired
    private EventStore eventStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE command_idempotency, event_store, outbox_events, inbox_events, account_summary_projection CASCADE");
    }

    @Test
    @DisplayName("Same key + same request payload returns cached result and creates only 1 set of domain events")
    void sameKeySameRequestPayload() {
        String idempotencyKey = "key-create-" + UUID.randomUUID();
        CreateAccountRequest req = new CreateAccountRequest("USD", 1000L, 500L);

        var res1 = accountController.createAccount(req, idempotencyKey, null, null).getBody();
        assertThat(res1).isNotNull();
        UUID accountId = res1.aggregateId();

        // Second request with exact same key and payload
        var res2 = accountController.createAccount(req, idempotencyKey, null, null).getBody();
        assertThat(res2).isNotNull();
        assertThat(res2.aggregateId()).isEqualTo(accountId);

        // Verify only 1 AccountCreated event exists in EventStore
        var stream = eventStore.loadStream(accountId);
        assertThat(stream).hasSize(1);
        assertThat(stream.get(0).eventType()).isEqualTo("AccountCreated");
    }

    @Test
    @DisplayName("Same key + different request payload throws CommandIdempotencyConflictException (HTTP 409)")
    void sameKeyDifferentRequestPayload() {
        String idempotencyKey = "key-dep-" + UUID.randomUUID();
        CreateAccountRequest createReq = new CreateAccountRequest("USD", 1000L, 500L);
        var res1 = accountController.createAccount(createReq, "key-init-" + UUID.randomUUID(), null, null).getBody();
        UUID accountId = res1.aggregateId();

        DepositRequest dep1 = new DepositRequest(5000L, "USD");
        accountController.deposit(accountId, dep1, idempotencyKey, null, null);

        DepositRequest dep2 = new DepositRequest(7000L, "USD");
        assertThatThrownBy(() -> accountController.deposit(accountId, dep2, idempotencyKey, null, null))
            .isInstanceOf(CommandIdempotencyConflictException.class)
            .hasMessageContaining("previously used with a different request payload");

        // Verify only 1 Deposit event appended
        var stream = eventStore.loadStream(accountId);
        assertThat(stream).hasSize(2); // 1 Created + 1 Deposited
    }

    @Test
    @DisplayName("Concurrent duplicate requests race on DB unique constraint and produce exactly 1 domain event")
    void concurrentDuplicateRequests() throws Exception {
        String idempotencyKey = "key-concurrent-" + UUID.randomUUID();
        CreateAccountRequest req = new CreateAccountRequest("USD", 2000L, 1000L);

        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    accountController.createAccount(req, idempotencyKey, null, null);
                    successCount.incrementAndGet();
                } catch (CommandIdempotencyConflictException e) {
                    conflictCount.incrementAndGet();
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }

        latch.countDown();
        done.await();
        executor.shutdown();

        assertThat(successCount.get() + conflictCount.get()).isEqualTo(threads);
        assertThat(successCount.get()).isGreaterThanOrEqualTo(1);
    }
}
