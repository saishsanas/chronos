package com.chronos.application.service;

import com.chronos.application.port.AccountSummaryProjectionRepository;
import com.chronos.application.port.EventStore;
import com.chronos.application.port.ProjectionRebuildJobRepository;
import com.chronos.domain.account.AccountReducer;
import com.chronos.domain.account.AccountState;
import com.chronos.domain.event.DomainEventEnvelope;
import com.chronos.domain.event.upcasting.EventUpcasterRegistry;
import com.chronos.domain.projection.AccountSummaryProjection;
import com.chronos.domain.projection.ProjectionRebuildJob;
import com.chronos.infrastructure.cache.RedisCacheService;
import com.chronos.infrastructure.observability.ChronosMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class ProjectionRebuildService {

    private static final Logger log = LoggerFactory.getLogger(ProjectionRebuildService.class);

    private final EventStore eventStore;
    private final AccountSummaryProjectionRepository projectionRepository;
    private final ProjectionRebuildJobRepository rebuildJobRepository;
    private final RedisCacheService redisCacheService;
    private final JdbcTemplate jdbcTemplate;
    private final ChronosMetrics metrics;
    private final EventUpcasterRegistry upcasterRegistry;

    public ProjectionRebuildService(
        EventStore eventStore,
        AccountSummaryProjectionRepository projectionRepository,
        ProjectionRebuildJobRepository rebuildJobRepository,
        RedisCacheService redisCacheService,
        JdbcTemplate jdbcTemplate
    ) {
        this(eventStore, projectionRepository, rebuildJobRepository, redisCacheService, jdbcTemplate, null, EventUpcasterRegistry.getInstance());
    }

    public ProjectionRebuildService(
        EventStore eventStore,
        AccountSummaryProjectionRepository projectionRepository,
        ProjectionRebuildJobRepository rebuildJobRepository,
        RedisCacheService redisCacheService,
        JdbcTemplate jdbcTemplate,
        @Autowired(required = false) ChronosMetrics metrics
    ) {
        this(eventStore, projectionRepository, rebuildJobRepository, redisCacheService, jdbcTemplate, metrics, EventUpcasterRegistry.getInstance());
    }

    @Autowired
    public ProjectionRebuildService(
        EventStore eventStore,
        AccountSummaryProjectionRepository projectionRepository,
        ProjectionRebuildJobRepository rebuildJobRepository,
        RedisCacheService redisCacheService,
        JdbcTemplate jdbcTemplate,
        @Autowired(required = false) ChronosMetrics metrics,
        @Autowired(required = false) EventUpcasterRegistry upcasterRegistry
    ) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore must not be null");
        this.projectionRepository = Objects.requireNonNull(projectionRepository, "projectionRepository must not be null");
        this.rebuildJobRepository = Objects.requireNonNull(rebuildJobRepository, "rebuildJobRepository must not be null");
        this.redisCacheService = Objects.requireNonNull(redisCacheService, "redisCacheService must not be null");
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.metrics = metrics;
        this.upcasterRegistry = upcasterRegistry != null ? upcasterRegistry : EventUpcasterRegistry.getInstance();
    }

    public ProjectionRebuildJob rebuildFull() {
        log.info("Starting full projection rebuild for account_summary_projection from EventStore");
        Instant start = Instant.now();
        ProjectionRebuildJob job = ProjectionRebuildJob.createFull("account_summary_projection");
        rebuildJobRepository.save(job);
        rebuildJobRepository.markStarted(job.jobId());

        try {
            // 1. Prepare isolated staging table
            projectionRepository.prepareStagingTable();

            // 2. Discover all distinct aggregate IDs from EventStore
            List<UUID> aggregateIds = jdbcTemplate.query(
                "SELECT DISTINCT aggregate_id FROM event_store",
                (rs, rowNum) -> rs.getObject("aggregate_id", UUID.class)
            );

            long totalEvents = 0;
            long maxSequence = 0;
            List<AccountSummaryProjection> stagedProjections = new ArrayList<>();

            // 3. Reconstruct canonical state for each aggregate directly from EventStore
            for (UUID accountId : aggregateIds) {
                List<DomainEventEnvelope> stream = eventStore.loadStream(accountId);
                totalEvents += stream.size();

                AccountState state = AccountState.uninitialized(accountId);
                for (DomainEventEnvelope envelope : stream) {
                    DomainEventEnvelope canonical = upcasterRegistry.upcastToCanonical(envelope);
                    state = AccountReducer.reduce(state, canonical);
                }

                if (state.sequenceNumber() > 0) {
                    AccountSummaryProjection projection = AccountSummaryProjection.fromAccountState(state, Instant.now());
                    stagedProjections.add(projection);
                    maxSequence = Math.max(maxSequence, state.sequenceNumber());
                }
            }

            // 4. Batch save staged projections into staging table
            projectionRepository.saveToStaging(stagedProjections);

            // 5. Execute atomic staging-to-live cutover
            projectionRepository.swapStagingToLive();

            // 6. Evict Redis cache to prevent stale pre-rebuild reads
            redisCacheService.clearAll();

            // 7. Mark job succeeded
            rebuildJobRepository.markSucceeded(job.jobId(), totalEvents, maxSequence);

            Duration duration = Duration.between(start, Instant.now());
            log.info("Full projection rebuild SUCCEEDED in {} ms (processed {} aggregates, {} events)",
                duration.toMillis(), aggregateIds.size(), totalEvents);

            if (metrics != null) {
                metrics.recordProjectionRebuildSuccess(duration);
            }

            return rebuildJobRepository.findById(job.jobId()).orElse(job);

        } catch (Exception e) {
            log.error("Full projection rebuild FAILED: {}", e.getMessage(), e);
            rebuildJobRepository.markFailed(job.jobId(), e.getMessage());
            if (metrics != null) {
                metrics.recordProjectionRebuildFailure();
            }
            throw new RuntimeException("Projection rebuild failed: " + e.getMessage(), e);
        }
    }

    public ProjectionRebuildJob rebuildTargeted(UUID accountId) {
        Objects.requireNonNull(accountId, "accountId must not be null");
        log.info("Starting targeted projection rebuild for aggregate {}", accountId);
        Instant start = Instant.now();
        ProjectionRebuildJob job = ProjectionRebuildJob.createTargeted("account_summary_projection", accountId);
        rebuildJobRepository.save(job);
        rebuildJobRepository.markStarted(job.jobId());

        try {
            List<DomainEventEnvelope> stream = eventStore.loadStream(accountId);
            AccountState state = AccountState.uninitialized(accountId);
            for (DomainEventEnvelope envelope : stream) {
                DomainEventEnvelope canonical = upcasterRegistry.upcastToCanonical(envelope);
                state = AccountReducer.reduce(state, canonical);
            }

            if (state.sequenceNumber() > 0) {
                AccountSummaryProjection projection = AccountSummaryProjection.fromAccountState(state, Instant.now());
                projectionRepository.save(projection);
            } else {
                projectionRepository.deleteByAccountId(accountId);
            }

            redisCacheService.evict(accountId);
            rebuildJobRepository.markSucceeded(job.jobId(), stream.size(), state.sequenceNumber());

            Duration duration = Duration.between(start, Instant.now());
            log.info("Targeted projection rebuild for aggregate {} SUCCEEDED in {} ms", accountId, duration.toMillis());

            if (metrics != null) {
                metrics.recordProjectionRebuildSuccess(duration);
            }

            return rebuildJobRepository.findById(job.jobId()).orElse(job);

        } catch (Exception e) {
            log.error("Targeted projection rebuild FAILED for aggregate {}: {}", accountId, e.getMessage(), e);
            rebuildJobRepository.markFailed(job.jobId(), e.getMessage());
            if (metrics != null) {
                metrics.recordProjectionRebuildFailure();
            }
            throw new RuntimeException("Targeted projection rebuild failed: " + e.getMessage(), e);
        }
    }
}
