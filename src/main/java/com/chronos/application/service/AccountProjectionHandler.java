package com.chronos.application.service;

import com.chronos.api.dto.AccountSummaryResponse;
import com.chronos.application.port.AccountSummaryProjectionRepository;
import com.chronos.application.port.DownstreamEventConsumer;
import com.chronos.domain.account.AccountReducer;
import com.chronos.domain.account.AccountState;
import com.chronos.domain.event.DomainEventEnvelope;
import com.chronos.domain.event.upcasting.EventUpcasterRegistry;
import com.chronos.domain.projection.AccountSummaryProjection;
import com.chronos.infrastructure.cache.RedisCacheService;
import com.chronos.infrastructure.observability.ChronosMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

@Service
public class AccountProjectionHandler implements DownstreamEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(AccountProjectionHandler.class);

    private final AccountSummaryProjectionRepository projectionRepository;
    private final RedisCacheService redisCacheService;
    private final ChronosMetrics metrics;
    private final EventUpcasterRegistry upcasterRegistry;

    public AccountProjectionHandler(
        AccountSummaryProjectionRepository projectionRepository,
        RedisCacheService redisCacheService
    ) {
        this(projectionRepository, redisCacheService, null, EventUpcasterRegistry.getInstance());
    }

    public AccountProjectionHandler(
        AccountSummaryProjectionRepository projectionRepository,
        RedisCacheService redisCacheService,
        @Autowired(required = false) ChronosMetrics metrics
    ) {
        this(projectionRepository, redisCacheService, metrics, EventUpcasterRegistry.getInstance());
    }

    @Autowired
    public AccountProjectionHandler(
        AccountSummaryProjectionRepository projectionRepository,
        RedisCacheService redisCacheService,
        @Autowired(required = false) ChronosMetrics metrics,
        @Autowired(required = false) EventUpcasterRegistry upcasterRegistry
    ) {
        this.projectionRepository = Objects.requireNonNull(projectionRepository, "projectionRepository must not be null");
        this.redisCacheService = Objects.requireNonNull(redisCacheService, "redisCacheService must not be null");
        this.metrics = metrics;
        this.upcasterRegistry = upcasterRegistry != null ? upcasterRegistry : EventUpcasterRegistry.getInstance();
    }

    @Override
    @Transactional
    public void onEvent(DomainEventEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope must not be null");

        try {
            Optional<AccountSummaryProjection> existing = projectionRepository.findByAccountId(envelope.aggregateId());
            AccountState currentState = existing.map(AccountSummaryProjection::toAccountState)
                .orElseGet(() -> AccountState.uninitialized(envelope.aggregateId()));

            // Upcast incoming envelope to current canonical representation before applying AccountReducer
            DomainEventEnvelope canonical = upcasterRegistry.upcastToCanonical(envelope);
            AccountState resultingState = AccountReducer.reduce(currentState, canonical);

            AccountSummaryProjection updatedProjection = AccountSummaryProjection.fromAccountState(resultingState, Instant.now());
            projectionRepository.save(updatedProjection);

            log.info("Projected event {} (type: {}, seq: {}) to account_summary_projection for account {}",
                envelope.eventId(), envelope.eventType(), envelope.sequenceNumber(), envelope.aggregateId());

            // Post-commit Redis cache update / refresh
            redisCacheService.put(envelope.aggregateId(), AccountSummaryResponse.fromProjection(updatedProjection));

            if (metrics != null) {
                metrics.recordProjectionProcessed();
            }
        } catch (Exception e) {
            log.error("Failed to process projection for event {}: {}", envelope.eventId(), e.getMessage());
            if (metrics != null) {
                metrics.recordProjectionFailed();
            }
            throw e;
        }
    }
}
