package com.chronos.application.service;

import com.chronos.api.dto.AccountSummaryResponse;
import com.chronos.application.port.AccountSummaryProjectionRepository;
import com.chronos.domain.account.exception.AccountNotFoundException;
import com.chronos.domain.projection.AccountSummaryProjection;
import com.chronos.infrastructure.cache.RedisCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class AccountSummaryQueryService {

    private static final Logger log = LoggerFactory.getLogger(AccountSummaryQueryService.class);

    private final AccountSummaryProjectionRepository projectionRepository;
    private final RedisCacheService redisCacheService;

    public AccountSummaryQueryService(
        AccountSummaryProjectionRepository projectionRepository,
        RedisCacheService redisCacheService
    ) {
        this.projectionRepository = Objects.requireNonNull(projectionRepository, "projectionRepository must not be null");
        this.redisCacheService = Objects.requireNonNull(redisCacheService, "redisCacheService must not be null");
    }

    public AccountSummaryResponse getAccountSummary(UUID accountId) {
        Objects.requireNonNull(accountId, "accountId must not be null");

        // 1. Try Redis cache first
        Optional<AccountSummaryResponse> cached = redisCacheService.get(accountId);
        if (cached.isPresent()) {
            log.debug("Serving account summary for {} from Redis cache", accountId);
            return cached.get();
        }

        // 2. Fallback to PostgreSQL read model projection
        log.debug("Querying PostgreSQL account_summary_projection for {}", accountId);
        Optional<AccountSummaryProjection> projectionOpt = projectionRepository.findByAccountId(accountId);
        if (projectionOpt.isEmpty()) {
            throw new AccountNotFoundException(accountId);
        }

        AccountSummaryResponse summary = AccountSummaryResponse.fromProjection(projectionOpt.get());

        // 3. Populate Redis cache on miss / fallback
        redisCacheService.put(accountId, summary);

        return summary;
    }
}
