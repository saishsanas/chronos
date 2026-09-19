package com.chronos.persistence.postgres;

import com.chronos.TestDatabaseHelper;
import com.chronos.api.controller.AccountController;
import com.chronos.api.dto.CreateAccountRequest;
import com.chronos.api.dto.DepositRequest;
import com.chronos.application.port.EventStore;
import com.chronos.application.port.OptimisticConcurrencyException;

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

@SpringBootTest
class OccConcurrencyIntegrationTest {

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
    @org.springframework.security.test.context.support.WithMockUser(username = "operator", roles = {"OPERATOR"})
    @DisplayName("Concurrent deposits against same aggregate trigger OCC conflict preventing silent lost updates")
    void occConcurrencyCheck() throws Exception {
        var createRes = accountController.createAccount(new CreateAccountRequest("USD", 1000L, 500L), null, null, null).getBody();
        UUID accountId = createRes.aggregateId();

        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger occConflictCount = new AtomicInteger(0);

        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();

        for (int i = 0; i < threads; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
                    latch.await();
                    // Each thread attempts deposit without unique idempotency key to force direct OCC race on event store
                    accountController.deposit(accountId, new DepositRequest(100L * (index + 1), "USD"), null, null, null);
                    successCount.incrementAndGet();
                } catch (OptimisticConcurrencyException e) {
                    occConflictCount.incrementAndGet();
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }

        latch.countDown();
        done.await();
        executor.shutdown();

        // Verify total attempts accounted for
        assertThat(successCount.get() + occConflictCount.get()).isEqualTo(threads);

        // Verify event store stream integrity: sequence numbers must be contiguous without gaps
        var stream = eventStore.loadStream(accountId);
        assertThat(stream).hasSize(1 + successCount.get()); // 1 Created + success deposits

        for (int i = 0; i < stream.size(); i++) {
            assertThat(stream.get(i).sequenceNumber()).isEqualTo(i + 1L);
        }
    }
}
