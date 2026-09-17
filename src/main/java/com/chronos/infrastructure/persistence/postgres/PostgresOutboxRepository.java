package com.chronos.infrastructure.persistence.postgres;

import com.chronos.application.port.OutboxRepository;
import com.chronos.domain.event.DomainEventEnvelope;
import com.chronos.domain.outbox.OutboxEventRecord;
import com.chronos.domain.outbox.OutboxStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Repository
public class PostgresOutboxRepository implements OutboxRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PostgresOutboxRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    @Transactional
    public int recoverExpiredLeases(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        String sql = """
            UPDATE outbox_events
            SET status = 'PENDING',
                locked_until = NULL,
                locked_by = NULL
            WHERE status = 'IN_FLIGHT'
              AND locked_until IS NOT NULL
              AND locked_until <= ?
            """;
        return jdbcTemplate.update(sql, Timestamp.from(now));
    }

    @Override
    @Transactional
    public List<OutboxEventRecord> claimDueBatch(int batchSize, String workerId, int leaseSeconds, Instant now) {
        Objects.requireNonNull(workerId, "workerId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (batchSize <= 0) {
            return List.of();
        }

        Instant lockedUntil = now.plusSeconds(leaseSeconds);

        String sql = """
            WITH due_rows AS (
                SELECT outbox_id
                FROM outbox_events
                WHERE (status = 'PENDING' AND next_attempt_at <= ?)
                   OR (status = 'IN_FLIGHT' AND locked_until IS NOT NULL AND locked_until <= ?)
                ORDER BY sequence_number ASC
                LIMIT ?
                FOR UPDATE SKIP LOCKED
            )
            UPDATE outbox_events o
            SET status = 'IN_FLIGHT',
                locked_until = ?,
                locked_by = ?,
                attempts = o.attempts + 1
            FROM due_rows
            WHERE o.outbox_id = due_rows.outbox_id
            RETURNING o.outbox_id, o.event_id, o.aggregate_id, o.aggregate_type, o.sequence_number,
                      o.event_type, o.event_version, o.recorded_at, o.event_envelope, o.status,
                      o.attempts, o.next_attempt_at, o.locked_until, o.locked_by, o.published_at,
                      o.last_error, o.created_at
            """;

        return jdbcTemplate.query(
            sql,
            new OutboxRowMapper(objectMapper),
            Timestamp.from(now),
            Timestamp.from(now),
            batchSize,
            Timestamp.from(lockedUntil),
            workerId
        );
    }

    @Override
    @Transactional
    public void markPublished(UUID outboxId, Instant publishedAt) {
        Objects.requireNonNull(outboxId, "outboxId must not be null");
        Objects.requireNonNull(publishedAt, "publishedAt must not be null");

        String sql = """
            UPDATE outbox_events
            SET status = 'PUBLISHED',
                published_at = ?,
                locked_until = NULL,
                locked_by = NULL,
                last_error = NULL
            WHERE outbox_id = ?
            """;
        jdbcTemplate.update(sql, Timestamp.from(publishedAt), outboxId);
    }

    @Override
    @Transactional
    public void markForRetry(UUID outboxId, int attempts, Instant nextAttemptAt, String lastError) {
        Objects.requireNonNull(outboxId, "outboxId must not be null");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt must not be null");

        String sql = """
            UPDATE outbox_events
            SET status = 'PENDING',
                attempts = ?,
                next_attempt_at = ?,
                locked_until = NULL,
                locked_by = NULL,
                last_error = ?
            WHERE outbox_id = ?
            """;
        jdbcTemplate.update(sql, attempts, Timestamp.from(nextAttemptAt), lastError, outboxId);
    }

    private static class OutboxRowMapper implements RowMapper<OutboxEventRecord> {

        private final ObjectMapper objectMapper;

        public OutboxRowMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public OutboxEventRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            UUID outboxId = rs.getObject("outbox_id", UUID.class);
            UUID eventId = rs.getObject("event_id", UUID.class);
            UUID aggregateId = rs.getObject("aggregate_id", UUID.class);
            String aggregateType = rs.getString("aggregate_type");
            long sequenceNumber = rs.getLong("sequence_number");
            String eventType = rs.getString("event_type");
            int eventVersion = rs.getInt("event_version");
            Timestamp recordedTs = rs.getTimestamp("recorded_at");
            Instant recordedAt = recordedTs.toInstant();
            String envelopeJson = rs.getString("event_envelope");
            String statusStr = rs.getString("status");
            OutboxStatus status = OutboxStatus.valueOf(statusStr);
            int attempts = rs.getInt("attempts");
            Timestamp nextAttemptTs = rs.getTimestamp("next_attempt_at");
            Instant nextAttemptAt = nextAttemptTs != null ? nextAttemptTs.toInstant() : recordedAt;

            Timestamp lockedUntilTs = rs.getTimestamp("locked_until");
            Instant lockedUntil = lockedUntilTs != null ? lockedUntilTs.toInstant() : null;
            String lockedBy = rs.getString("locked_by");

            Timestamp publishedTs = rs.getTimestamp("published_at");
            Instant publishedAt = publishedTs != null ? publishedTs.toInstant() : null;

            String lastError = rs.getString("last_error");

            Timestamp createdTs = rs.getTimestamp("created_at");
            Instant createdAt = createdTs.toInstant();

            try {
                DomainEventEnvelope envelope = objectMapper.readValue(envelopeJson, DomainEventEnvelope.class);
                return new OutboxEventRecord(
                    outboxId, eventId, aggregateId, aggregateType, sequenceNumber,
                    eventType, eventVersion, recordedAt, envelope, status, attempts,
                    nextAttemptAt, lockedUntil, lockedBy, publishedAt, lastError, createdAt
                );
            } catch (JsonProcessingException e) {
                throw new SQLException("Failed to deserialize event_envelope JSON for outbox_id " + outboxId, e);
            }
        }
    }
}
