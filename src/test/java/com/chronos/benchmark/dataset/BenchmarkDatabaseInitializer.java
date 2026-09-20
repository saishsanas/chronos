package com.chronos.benchmark.dataset;

import com.chronos.domain.account.AccountReducer;
import com.chronos.domain.account.AccountState;
import com.chronos.domain.event.DomainEventEnvelope;
import com.chronos.domain.snapshot.Snapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class BenchmarkDatabaseInitializer {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkDatabaseInitializer.class);

    public static final String BENCHMARK_DB_NAME = "chronos_bench_db";
    public static final String BENCHMARK_DB_URL = "jdbc:postgresql://localhost:5432/" + BENCHMARK_DB_NAME;
    private static final String DEFAULT_USER = "chronos_user";
    private static final String DEFAULT_PASS = "chronos_password";
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    public static void initializeBenchmarkDatabase() {
        String containerMaintenanceUrl = "jdbc:postgresql://localhost:5432/chronos_db";

        // 1. Create chronos_bench_db if it does not already exist
        try (Connection conn = DriverManager.getConnection(containerMaintenanceUrl, DEFAULT_USER, DEFAULT_PASS);
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("CREATE DATABASE " + BENCHMARK_DB_NAME);
            log.info("Created isolated benchmark database: {}", BENCHMARK_DB_NAME);
        } catch (Exception e) {
            // Already exists or connection created elsewhere
            log.debug("Database {} check/creation notice: {}", BENCHMARK_DB_NAME, e.getMessage());
        }

        // 2. Run Flyway migrations on chronos_bench_db
        log.info("Running Flyway migrations on {}", BENCHMARK_DB_URL);
        Flyway flyway = Flyway.configure()
            .dataSource(BENCHMARK_DB_URL, DEFAULT_USER, DEFAULT_PASS)
            .locations("classpath:db/migration")
            .load();
        flyway.migrate();
        log.info("Flyway migrations completed for {}", BENCHMARK_DB_NAME);
    }

    /**
     * Safety-gated clean reset of benchmark tables.
     * Aborts immediately if URL is not chronos_bench_db.
     */
    public static void resetBenchmarkDatabase() {
        if (!BENCHMARK_DB_URL.endsWith("/" + BENCHMARK_DB_NAME)) {
            throw new IllegalStateException("SAFETY ABORT: Refusing destructive operation on non-benchmark database: " + BENCHMARK_DB_URL);
        }

        log.info("Resetting isolated benchmark tables in {}", BENCHMARK_DB_NAME);
        String truncateSql = """
            DROP TABLE IF EXISTS account_summary_projection_staging;
            TRUNCATE TABLE
                event_store,
                snapshots,
                outbox_events,
                inbox_events,
                account_summary_projection,
                command_idempotency,
                projection_rebuild_jobs,
                security_audit_log
            CASCADE;
            """;

        try (Connection conn = DriverManager.getConnection(BENCHMARK_DB_URL, DEFAULT_USER, DEFAULT_PASS);
             Statement stmt = conn.createStatement()) {
            stmt.execute(truncateSql);
            log.info("Reset completed successfully for {}", BENCHMARK_DB_NAME);
        } catch (Exception e) {
            throw new RuntimeException("Failed to reset benchmark database: " + e.getMessage(), e);
        }
    }

    /**
     * Fast batch insertion of events into event_store
     */
    public static void batchInsertEvents(List<DomainEventEnvelope> events) {
        String sql = """
            INSERT INTO event_store (
                event_id, aggregate_id, aggregate_type, sequence_number,
                event_type, event_version, recorded_at, metadata, payload
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
            """;

        try (Connection conn = DriverManager.getConnection(BENCHMARK_DB_URL, DEFAULT_USER, DEFAULT_PASS);
             PreparedStatement ps = conn.prepareStatement(sql)) {

            conn.setAutoCommit(false);
            int batchCount = 0;

            for (DomainEventEnvelope e : events) {
                ps.setObject(1, e.eventId());
                ps.setObject(2, e.aggregateId());
                ps.setString(3, e.aggregateType());
                ps.setLong(4, e.sequenceNumber());
                ps.setString(5, e.eventType());
                ps.setInt(6, e.eventVersion());
                ps.setTimestamp(7, Timestamp.from(e.recordedAt()));
                ps.setString(8, MAPPER.writeValueAsString(e.metadata()));
                ps.setString(9, MAPPER.writeValueAsString(e.payload()));
                ps.addBatch();
                batchCount++;

                if (batchCount % 2000 == 0) {
                    ps.executeBatch();
                }
            }

            ps.executeBatch();
            conn.commit();
            log.info("Successfully batch inserted {} events into {}", events.size(), BENCHMARK_DB_NAME);

        } catch (Exception e) {
            throw new RuntimeException("Failed to batch insert benchmark events: " + e.getMessage(), e);
        }
    }

    /**
     * Persists a deterministic snapshot for single aggregate up to targetSequence
     */
    public static void createAndInsertSnapshot(UUID aggregateId, List<DomainEventEnvelope> events, long targetSequence) {
        if (targetSequence < 1 || targetSequence > events.size()) {
            throw new IllegalArgumentException("targetSequence out of bounds: " + targetSequence);
        }

        AccountState state = AccountState.uninitialized(aggregateId);
        for (int i = 0; i < targetSequence; i++) {
            state = AccountReducer.reduce(state, events.get(i));
        }

        Snapshot snapshot = Snapshot.create(state);

        String sql = """
            INSERT INTO snapshots (
                snapshot_id, aggregate_id, sequence_number, snapshot_version,
                domain_version, replay_logic_hash, created_at, state_payload
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            """;

        try (Connection conn = DriverManager.getConnection(BENCHMARK_DB_URL, DEFAULT_USER, DEFAULT_PASS);
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setObject(1, snapshot.snapshotId());
            ps.setObject(2, snapshot.aggregateId());
            ps.setLong(3, snapshot.sequenceNumber());
            ps.setInt(4, snapshot.snapshotVersion());
            ps.setInt(5, snapshot.domainVersion());
            ps.setString(6, snapshot.replayLogicHash());
            ps.setTimestamp(7, Timestamp.from(snapshot.createdAt()));
            ps.setString(8, MAPPER.writeValueAsString(snapshot.state()));
            ps.executeUpdate();

            log.info("Persisted benchmark snapshot for aggregate {} at sequence {}", aggregateId, targetSequence);

        } catch (Exception e) {
            throw new RuntimeException("Failed to insert benchmark snapshot: " + e.getMessage(), e);
        }
    }
}
