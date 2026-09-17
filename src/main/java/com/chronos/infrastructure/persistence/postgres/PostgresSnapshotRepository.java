package com.chronos.infrastructure.persistence.postgres;

import com.chronos.application.port.SnapshotRepository;
import com.chronos.domain.account.AccountState;
import com.chronos.domain.snapshot.Snapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Optional;
import java.util.UUID;

@Repository
public class PostgresSnapshotRepository implements SnapshotRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PostgresSnapshotRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    @Transactional
    public void save(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");

        String sql = """
            INSERT INTO snapshots (
                snapshot_id, aggregate_id, sequence_number, snapshot_version,
                domain_version, replay_logic_hash, created_at, state_payload
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            """;

        try {
            jdbcTemplate.update(
                sql,
                snapshot.snapshotId(),
                snapshot.aggregateId(),
                snapshot.sequenceNumber(),
                snapshot.snapshotVersion(),
                snapshot.domainVersion(),
                snapshot.replayLogicHash(),
                Timestamp.from(snapshot.createdAt()),
                toJsonString(snapshot.state())
            );
        } catch (DuplicateKeyException e) {
            // Immutable snapshot already exists for (aggregate_id, sequence_number, snapshot_version)
            // Silently ignore per snapshot immutability semantics
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Snapshot> findLatestForAggregate(UUID aggregateId) {
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");

        String sql = """
            SELECT snapshot_id, aggregate_id, sequence_number, snapshot_version,
                   domain_version, replay_logic_hash, created_at, state_payload
            FROM snapshots
            WHERE aggregate_id = ?
            ORDER BY sequence_number DESC
            LIMIT 1
            """;

        List<Snapshot> results = jdbcTemplate.query(sql, new SnapshotRowMapper(objectMapper), aggregateId);
        return results.stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Snapshot> findLatestAtOrBeforeSequence(UUID aggregateId, long sequenceNumber) {
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");

        String sql = """
            SELECT snapshot_id, aggregate_id, sequence_number, snapshot_version,
                   domain_version, replay_logic_hash, created_at, state_payload
            FROM snapshots
            WHERE aggregate_id = ? AND sequence_number <= ?
            ORDER BY sequence_number DESC
            LIMIT 1
            """;

        List<Snapshot> results = jdbcTemplate.query(sql, new SnapshotRowMapper(objectMapper), aggregateId, sequenceNumber);
        return results.stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Snapshot> findLatestAtOrBeforeTimestamp(UUID aggregateId, Instant timestamp) {
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");

        // A snapshot represents event state history up to state.lastUpdatedAt.
        // We match snapshots whose represented state.lastUpdatedAt is <= timestamp.
        String sql = """
            SELECT snapshot_id, aggregate_id, sequence_number, snapshot_version,
                   domain_version, replay_logic_hash, created_at, state_payload
            FROM snapshots
            WHERE aggregate_id = ? AND (state_payload ->> 'lastUpdatedAt')::timestamptz <= ?
            ORDER BY sequence_number DESC
            LIMIT 1
            """;

        List<Snapshot> results = jdbcTemplate.query(sql, new SnapshotRowMapper(objectMapper), aggregateId, Timestamp.from(timestamp));
        return results.stream().findFirst();
    }


    private String toJsonString(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize snapshot state_payload JSON", e);
        }
    }

    private static class SnapshotRowMapper implements RowMapper<Snapshot> {

        private final ObjectMapper objectMapper;

        public SnapshotRowMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public Snapshot mapRow(ResultSet rs, int rowNum) throws SQLException {
            UUID snapshotId = rs.getObject("snapshot_id", UUID.class);
            UUID aggregateId = rs.getObject("aggregate_id", UUID.class);
            long sequenceNumber = rs.getLong("sequence_number");
            int snapshotVersion = rs.getInt("snapshot_version");
            int domainVersion = rs.getInt("domain_version");
            String replayLogicHash = rs.getString("replay_logic_hash");
            Timestamp timestamp = rs.getTimestamp("created_at");
            Instant createdAt = timestamp.toInstant();
            String statePayloadJson = rs.getString("state_payload");

            try {
                AccountState state = objectMapper.readValue(statePayloadJson, AccountState.class);
                return new Snapshot(
                    snapshotId,
                    aggregateId,
                    sequenceNumber,
                    snapshotVersion,
                    domainVersion,
                    replayLogicHash,
                    createdAt,
                    state
                );
            } catch (JsonProcessingException e) {
                throw new SQLException("Failed to deserialize snapshot state_payload JSON for snapshot " + snapshotId, e);
            }
        }
    }
}
