package com.chronos.benchmark.integration;

import com.chronos.application.port.*;
import com.chronos.application.service.*;
import com.chronos.benchmark.dataset.BenchmarkDatabaseInitializer;
import com.chronos.domain.event.upcasting.EventSchemaRegistry;
import com.chronos.domain.event.upcasting.EventUpcasterRegistry;
import com.chronos.domain.event.upcasting.MoneyDepositedV1ToV2Upcaster;
import com.chronos.infrastructure.cache.RedisCacheService;
import com.chronos.infrastructure.observability.ChronosMetrics;
import com.chronos.infrastructure.persistence.postgres.*;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;

public class BenchmarkContext implements AutoCloseable {

    private final HikariDataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final LettuceConnectionFactory redisConnectionFactory;
    private final StringRedisTemplate redisTemplate;
    private final RedisCacheService redisCacheService;
    private final EventUpcasterRegistry upcasterRegistry;
    private final ChronosMetrics metrics;

    private final PostgresEventStore eventStore;
    private final PostgresSnapshotRepository snapshotRepository;
    private final PostgresAccountSummaryProjectionRepository projectionRepository;
    private final PostgresProjectionRebuildJobRepository rebuildJobRepository;
    private final PostgresCommandIdempotencyRepository idempotencyRepository;
    private final PostgresSecurityAuditRepository securityAuditRepository;

    private final TemporalStateReconstructor reconstructor;
    private final ProjectionRebuildService projectionRebuildService;
    private final AccountSummaryQueryService accountSummaryQueryService;
    private final CommandIdempotencyService idempotencyService;
    private final SecurityAuditService auditService;
    private final AccountCommandProcessor commandProcessor;

    public BenchmarkContext() {
        // 1. Initialize DB & Flyway schema if needed
        BenchmarkDatabaseInitializer.initializeBenchmarkDatabase();

        // 2. HikariCP connection pool tuned for benchmark throughput
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(BenchmarkDatabaseInitializer.BENCHMARK_DB_URL);
        config.setUsername("chronos_user");
        config.setPassword("chronos_password");
        config.setMaximumPoolSize(20);
        config.setMinimumIdle(5);
        config.setPoolName("ChronosBenchmarkHikariPool");
        config.setConnectionTimeout(30000);
        this.dataSource = new HikariDataSource(config);
        this.jdbcTemplate = new JdbcTemplate(dataSource);

        // 3. Jackson ObjectMapper
        this.objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        // 4. Redis connection
        LettuceConnectionFactory lcf = null;
        StringRedisTemplate srt = null;
        try {
            RedisStandaloneConfiguration redisConfig = new RedisStandaloneConfiguration("localhost", 6379);
            lcf = new LettuceConnectionFactory(redisConfig);
            lcf.afterPropertiesSet();
            srt = new StringRedisTemplate(lcf);
            srt.afterPropertiesSet();
        } catch (Exception e) {
            System.err.println("Notice: Redis not available for benchmark: " + e.getMessage());
        }
        this.redisConnectionFactory = lcf;
        this.redisTemplate = srt;

        this.metrics = new ChronosMetrics(new SimpleMeterRegistry());
        this.redisCacheService = new RedisCacheService(redisTemplate, objectMapper, metrics, Duration.ofSeconds(60));

        // 5. Schema and Upcasters
        EventSchemaRegistry schemaRegistry = EventSchemaRegistry.getInstance();
        this.upcasterRegistry = new EventUpcasterRegistry(schemaRegistry, List.of(new MoneyDepositedV1ToV2Upcaster()), metrics);

        // 6. Persistence repositories
        this.eventStore = new PostgresEventStore(jdbcTemplate, objectMapper);
        this.snapshotRepository = new PostgresSnapshotRepository(jdbcTemplate, objectMapper);
        this.projectionRepository = new PostgresAccountSummaryProjectionRepository(jdbcTemplate);
        this.rebuildJobRepository = new PostgresProjectionRebuildJobRepository(jdbcTemplate);
        this.idempotencyRepository = new PostgresCommandIdempotencyRepository(jdbcTemplate);
        this.securityAuditRepository = new PostgresSecurityAuditRepository(jdbcTemplate);

        // 7. Domain services
        this.reconstructor = new TemporalStateReconstructor(eventStore, snapshotRepository, upcasterRegistry);
        this.projectionRebuildService = new ProjectionRebuildService(
            eventStore, projectionRepository, rebuildJobRepository, redisCacheService, jdbcTemplate, metrics, upcasterRegistry
        );
        this.accountSummaryQueryService = new AccountSummaryQueryService(projectionRepository, redisCacheService);
        this.idempotencyService = new CommandIdempotencyService(idempotencyRepository, objectMapper, metrics);
        this.auditService = new SecurityAuditService(securityAuditRepository, metrics);
        this.commandProcessor = new AccountCommandProcessor(eventStore, objectMapper, snapshotRepository, 100, metrics, upcasterRegistry);
    }

    public JdbcTemplate getJdbcTemplate() { return jdbcTemplate; }
    public ObjectMapper getObjectMapper() { return objectMapper; }
    public StringRedisTemplate getRedisTemplate() { return redisTemplate; }
    public RedisCacheService getRedisCacheService() { return redisCacheService; }
    public EventUpcasterRegistry getUpcasterRegistry() { return upcasterRegistry; }
    public PostgresEventStore getEventStore() { return eventStore; }
    public PostgresSnapshotRepository getSnapshotRepository() { return snapshotRepository; }
    public PostgresAccountSummaryProjectionRepository getProjectionRepository() { return projectionRepository; }
    public TemporalStateReconstructor getReconstructor() { return reconstructor; }
    public ProjectionRebuildService getProjectionRebuildService() { return projectionRebuildService; }
    public AccountSummaryQueryService getAccountSummaryQueryService() { return accountSummaryQueryService; }
    public CommandIdempotencyService getIdempotencyService() { return idempotencyService; }
    public SecurityAuditService getAuditService() { return auditService; }
    public AccountCommandProcessor getCommandProcessor() { return commandProcessor; }

    @Override
    public void close() {
        if (redisConnectionFactory != null) {
            try {
                redisConnectionFactory.destroy();
            } catch (Exception ignored) {}
        }
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
