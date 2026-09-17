package com.chronos.infrastructure.persistence.postgres;

import com.chronos.application.port.InboxRepository;
import com.chronos.domain.inbox.InboxEventRecord;
import com.chronos.domain.inbox.InboxStatus;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PostgresInboxRepository implements InboxRepository {

    private final JdbcTemplate jdbcTemplate;

    public PostgresInboxRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<InboxEventRecord> findByEventId(UUID eventId) {
        Objects.requireNonNull(eventId, "eventId must not be null");
        String sql = """
            SELECT inbox_id, event_id, aggregate_id, aggregate_type, sequence_number,
                   event_type, event_version, received_at, processed_at, status, attempts, last_error
            FROM inbox_events
            WHERE event_id = ?
            """;
        try {
            InboxEventRecord record = jdbcTemplate.queryForObject(sql, new InboxRowMapper(), eventId);
            return Optional.ofNullable(record);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    @Transactional
    public InboxEventRecord save(InboxEventRecord record) {
        Objects.requireNonNull(record, "record must not be null");
        String sql = """
            INSERT INTO inbox_events (
                inbox_id, event_id, aggregate_id, aggregate_type, sequence_number,
                event_type, event_version, received_at, processed_at, status, attempts, last_error
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        jdbcTemplate.update(
            sql,
            record.inboxId(),
            record.eventId(),
            record.aggregateId(),
            record.aggregateType(),
            record.sequenceNumber(),
            record.eventType(),
            record.eventVersion(),
            Timestamp.from(record.receivedAt()),
            record.processedAt() != null ? Timestamp.from(record.processedAt()) : null,
            record.status().name(),
            record.attempts(),
            record.lastError()
        );

        return record;
    }

    @Override
    @Transactional
    public void markProcessed(UUID inboxId, Instant processedAt) {
        Objects.requireNonNull(inboxId, "inboxId must not be null");
        Objects.requireNonNull(processedAt, "processedAt must not be null");
        String sql = """
            UPDATE inbox_events
            SET status = 'PROCESSED',
                processed_at = ?,
                last_error = NULL
            WHERE inbox_id = ?
            """;
        jdbcTemplate.update(sql, Timestamp.from(processedAt), inboxId);
    }

    @Override
    @Transactional
    public void markFailed(UUID inboxId, String lastError) {
        Objects.requireNonNull(inboxId, "inboxId must not be null");
        String sql = """
            UPDATE inbox_events
            SET attempts = attempts + 1,
                last_error = ?
            WHERE inbox_id = ?
            """;
        jdbcTemplate.update(sql, lastError, inboxId);
    }

    @Override
    @Transactional(readOnly = true)
    public long getLastProcessedSequence(UUID aggregateId) {
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        String sql = "SELECT COALESCE(MAX(last_sequence_number), 0) FROM consumer_aggregate_state WHERE aggregate_id = ?";
        Long seq = jdbcTemplate.queryForObject(sql, Long.class, aggregateId);
        return seq != null ? seq : 0L;
    }

    @Override
    @Transactional
    public void updateConsumerSequence(UUID aggregateId, long sequenceNumber, Instant updatedAt) {
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        String sql = """
            INSERT INTO consumer_aggregate_state (aggregate_id, last_sequence_number, updated_at)
            VALUES (?, ?, ?)
            ON CONFLICT (aggregate_id)
            DO UPDATE SET last_sequence_number = EXCLUDED.last_sequence_number,
                          updated_at = EXCLUDED.updated_at
            """;
        jdbcTemplate.update(sql, aggregateId, sequenceNumber, Timestamp.from(updatedAt));
    }

    private static class InboxRowMapper implements RowMapper<InboxEventRecord> {
        @Override
        public InboxEventRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            UUID inboxId = rs.getObject("inbox_id", UUID.class);
            UUID eventId = rs.getObject("event_id", UUID.class);
            UUID aggregateId = rs.getObject("aggregate_id", UUID.class);
            String aggregateType = rs.getString("aggregate_type");
            long sequenceNumber = rs.getLong("sequence_number");
            String eventType = rs.getString("event_type");
            int eventVersion = rs.getInt("event_version");

            Timestamp receivedTs = rs.getTimestamp("received_at");
            Instant receivedAt = receivedTs.toInstant();

            Timestamp processedTs = rs.getTimestamp("processed_at");
            Instant processedAt = processedTs != null ? processedTs.toInstant() : null;

            String statusStr = rs.getString("status");
            InboxStatus status = InboxStatus.valueOf(statusStr);

            int attempts = rs.getInt("attempts");
            String lastError = rs.getString("last_error");

            return new InboxEventRecord(
                inboxId, eventId, aggregateId, aggregateType, sequenceNumber,
                eventType, eventVersion, receivedAt, processedAt, status, attempts, lastError
            );
        }
    }
}
