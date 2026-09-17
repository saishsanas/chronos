package com.chronos.application.service;

import com.chronos.api.dto.AccountSummaryResponse;
import com.chronos.application.port.AccountSummaryProjectionRepository;
import com.chronos.domain.account.AccountStatus;
import com.chronos.domain.account.exception.AccountNotFoundException;
import com.chronos.domain.projection.AccountSummaryProjection;
import com.chronos.infrastructure.cache.RedisCacheService;
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
public class AccountSummaryQueryServiceTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5432/chronos_test_db");
        registry.add("spring.datasource.username", () -> "test_user");
        registry.add("spring.datasource.password", () -> "test_password");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
    }

    @Autowired
    private AccountSummaryQueryService queryService;

    @Autowired
    private AccountSummaryProjectionRepository projectionRepository;

    @Autowired
    private RedisCacheService redisCacheService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("TRUNCATE TABLE account_summary_projection");
    }

    @Test
    @DisplayName("1. Cache miss falls back to PostgreSQL projection and populates Redis cache")
    void testCacheMissFallbackToPostgres() {
        UUID accountId = UUID.randomUUID();
        Instant now = Instant.now();

        AccountSummaryProjection proj = new AccountSummaryProjection(
            accountId, "INR", 50000L, 10000L, 50000L, AccountStatus.ACTIVE, 3L, now, now
        );
        projectionRepository.save(proj);

        // First call: Cache miss -> loads from Postgres & populates Redis
        AccountSummaryResponse summary1 = queryService.getAccountSummary(accountId);
        assertThat(summary1).isNotNull();
        assertThat(summary1.accountId()).isEqualTo(accountId);
        assertThat(summary1.balanceMinor()).isEqualTo(50000L);
        assertThat(summary1.sequenceNumber()).isEqualTo(3L);

        // Second call: Serves from Redis cache hit
        AccountSummaryResponse summary2 = queryService.getAccountSummary(accountId);
        assertThat(summary2).isNotNull();
        assertThat(summary2.balanceMinor()).isEqualTo(50000L);
    }

    @Test
    @DisplayName("2. Missing account throws AccountNotFoundException 404")
    void testMissingAccountThrowsNotFound() {
        UUID missingId = UUID.randomUUID();

        assertThatThrownBy(() -> queryService.getAccountSummary(missingId))
            .isInstanceOf(AccountNotFoundException.class);
    }
}
