package com.chronos.infrastructure.persistence.postgres;

import com.chronos.application.port.EventStore;
import com.chronos.application.port.OptimisticConcurrencyException;
import com.chronos.domain.event.DomainEventEnvelope;
import com.chronos.domain.event.EventMetadata;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
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
public class PostgresEventStore implements EventStore {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PostgresEventStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public long currentVersion(UUID aggregateId) {
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        String sql = "SELECT COALESCE(MAX(sequence_number), 0) FROM event_store WHERE aggregate_id = ?";
        Long version = jdbcTemplate.queryForObject(sql, Long.class, aggregateId);
        return version != null ? version : 0L;
    }

    @Override
    @Transactional
    public void append(UUID aggregateId, long expectedVersion, List<DomainEventEnvelope> events) {
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        Objects.requireNonNull(events, "events must not be null");

        if (events.isEmpty()) {
            return;
        }

        // 1. Verify expected version against current database state
        long currentVer = currentVersion(aggregateId);
        if (currentVer != expectedVersion) {
            throw new OptimisticConcurrencyException(
                "Concurrency conflict for aggregate " + aggregateId +
                ": expected version " + expectedVersion + " but current version is " + currentVer
            );
        }

        // 2. Validate batch sequence numbers and aggregate ID consistency
        long expectedSequence = expectedVersion + 1;
        for (DomainEventEnvelope event : events) {
            if (!aggregateId.equals(event.aggregateId())) {
                throw new IllegalArgumentException(
                    "Event aggregateId " + event.aggregateId() + " does not match target aggregateId " + aggregateId
                );
            }
            if (event.sequenceNumber() != expectedSequence) {
                throw new IllegalArgumentException(
                    "Invalid event sequence number in batch: expected " + expectedSequence + " but got " + event.sequenceNumber()
                );
            }
            expectedSequence++;
        }

        // 3. Execute atomic batch insert into event_store
        String sql = """
            INSERT INTO event_store (
                event_id, aggregate_id, aggregate_type, sequence_number,
                event_type, event_version, recorded_at, metadata, payload
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
            """;

        try {
            jdbcTemplate.batchUpdate(
                sql,
                events,
                events.size(),
                (ps, event) -> {
                    ps.setObject(1, event.eventId());
                    ps.setObject(2, event.aggregateId());
                    ps.setString(3, event.aggregateType());
                    ps.setLong(4, event.sequenceNumber());
                    ps.setString(5, event.eventType());
                    ps.setInt(6, event.eventVersion());
                    ps.setTimestamp(7, Timestamp.from(event.recordedAt()));
                    ps.setString(8, toJsonString(event.metadata()));
                    ps.setString(9, toJsonString(event.payload()));
                }
            );
        } catch (DuplicateKeyException e) {
            throw new OptimisticConcurrencyException(
                "Concurrency conflict appending to aggregate " + aggregateId + ": sequence constraint violated", e
            );
        } catch (DataAccessException e) {
            // Check for unique constraint violation in case of generic DataAccessException wrapping
            if (e.getMessage() != null && (e.getMessage().contains("uk_aggregate_sequence") || e.getMessage().contains("duplicate key"))) {
                throw new OptimisticConcurrencyException(
                    "Concurrency conflict appending to aggregate " + aggregateId + ": sequence constraint violated", e
                );
            }
            throw e;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<DomainEventEnvelope> loadStream(UUID aggregateId) {
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        String sql = """
            SELECT event_id, aggregate_id, aggregate_type, sequence_number,
                   event_type, event_version, recorded_at, metadata, payload
            FROM event_store
            WHERE aggregate_id = ?
            ORDER BY sequence_number ASC
            """;

        return jdbcTemplate.query(sql, new EventEnvelopeRowMapper(objectMapper), aggregateId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DomainEventEnvelope> loadStreamUpTo(UUID aggregateId, Instant timestamp) {
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");

        String sql = """
            SELECT event_id, aggregate_id, aggregate_type, sequence_number,
                   event_type, event_version, recorded_at, metadata, payload
            FROM event_store
            WHERE aggregate_id = ? AND recorded_at <= ?
            ORDER BY sequence_number ASC
            """;

        return jdbcTemplate.query(sql, new EventEnvelopeRowMapper(objectMapper), aggregateId, Timestamp.from(timestamp));
    }

    @Override
    @Transactional(readOnly = true)
    public List<DomainEventEnvelope> loadStreamFrom(UUID aggregateId, long fromSequenceNumber) {
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");

        String sql = """
            SELECT event_id, aggregate_id, aggregate_type, sequence_number,
                   event_type, event_version, recorded_at, metadata, payload
            FROM event_store
            WHERE aggregate_id = ? AND sequence_number > ?
            ORDER BY sequence_number ASC
            """;

        return jdbcTemplate.query(sql, new EventEnvelopeRowMapper(objectMapper), aggregateId, fromSequenceNumber);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DomainEventEnvelope> loadStreamFromAndUpTo(UUID aggregateId, long fromSequenceNumber, Instant timestamp) {
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");

        String sql = """
            SELECT event_id, aggregate_id, aggregate_type, sequence_number,
                   event_type, event_version, recorded_at, metadata, payload
            FROM event_store
            WHERE aggregate_id = ? AND sequence_number > ? AND recorded_at <= ?
            ORDER BY sequence_number ASC
            """;

        return jdbcTemplate.query(sql, new EventEnvelopeRowMapper(objectMapper), aggregateId, fromSequenceNumber, Timestamp.from(timestamp));
    }


    private String toJsonString(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize JSON payload/metadata", e);
        }
    }

    private static class EventEnvelopeRowMapper implements RowMapper<DomainEventEnvelope> {

        private final ObjectMapper objectMapper;

        public EventEnvelopeRowMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public DomainEventEnvelope mapRow(ResultSet rs, int rowNum) throws SQLException {
            UUID eventId = rs.getObject("event_id", UUID.class);
            UUID aggregateId = rs.getObject("aggregate_id", UUID.class);
            String aggregateType = rs.getString("aggregate_type");
            long sequenceNumber = rs.getLong("sequence_number");
            String eventType = rs.getString("event_type");
            int eventVersion = rs.getInt("event_version");
            Timestamp timestamp = rs.getTimestamp("recorded_at");
            Instant recordedAt = timestamp.toInstant();

            String metadataJson = rs.getString("metadata");
            String payloadJson = rs.getString("payload");

            try {
                EventMetadata metadata = objectMapper.readValue(metadataJson, EventMetadata.class);
                JsonNode payload = objectMapper.readTree(payloadJson);

                return new DomainEventEnvelope(
                    eventId,
                    aggregateId,
                    aggregateType,
                    sequenceNumber,
                    eventType,
                    eventVersion,
                    recordedAt,
                    metadata,
                    payload
                );
            } catch (JsonProcessingException e) {
                throw new SQLException("Failed to deserialize event metadata or payload JSON for event " + eventId, e);
            }
        }
    }
}
