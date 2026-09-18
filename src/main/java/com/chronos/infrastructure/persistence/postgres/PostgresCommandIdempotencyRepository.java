package com.chronos.infrastructure.persistence.postgres;

import com.chronos.application.port.CommandIdempotencyRepository;
import com.chronos.domain.idempotency.CommandIdempotencyRecord;
import com.chronos.domain.idempotency.IdempotencyStatus;
import org.springframework.dao.DuplicateKeyException;
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
public class PostgresCommandIdempotencyRepository implements CommandIdempotencyRepository {

    private final JdbcTemplate jdbcTemplate;

    public PostgresCommandIdempotencyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    @Override
    @Transactional
    public boolean tryInsertInFlight(CommandIdempotencyRecord record) {
        Objects.requireNonNull(record, "record must not be null");
        String sql = """
            INSERT INTO command_idempotency (
                idempotency_id, actor_id, idempotency_key, request_hash, command_type,
                aggregate_id, status, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try {
            jdbcTemplate.update(
                sql,
                record.idempotencyId(),
                record.actorId(),
                record.idempotencyKey(),
                record.requestHash(),
                record.commandType(),
                record.aggregateId(),
                record.status().name(),
                Timestamp.from(record.createdAt())
            );
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CommandIdempotencyRecord> findByActorAndKey(String actorId, String idempotencyKey) {
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        String sql = """
            SELECT idempotency_id, actor_id, idempotency_key, request_hash, command_type,
                   aggregate_id, status, response_status, response_payload, created_at, completed_at, expires_at
            FROM command_idempotency
            WHERE actor_id = ? AND idempotency_key = ?
            """;
        try {
            CommandIdempotencyRecord record = jdbcTemplate.queryForObject(sql, new RecordRowMapper(), actorId, idempotencyKey);
            return Optional.ofNullable(record);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    @Transactional
    public void markCompleted(CommandIdempotencyRecord record, int responseStatus, String responsePayload) {
        Objects.requireNonNull(record, "record must not be null");
        String sql = """
            UPDATE command_idempotency
            SET status = ?, response_status = ?, response_payload = ?::jsonb, completed_at = ?
            WHERE actor_id = ? AND idempotency_key = ?
            """;
        jdbcTemplate.update(
            sql,
            IdempotencyStatus.COMPLETED.name(),
            responseStatus,
            responsePayload,
            Timestamp.from(Instant.now()),
            record.actorId(),
            record.idempotencyKey()
        );
    }

    @Override
    @Transactional
    public void markFailed(CommandIdempotencyRecord record, String errorDetails) {
        Objects.requireNonNull(record, "record must not be null");
        String sql = """
            UPDATE command_idempotency
            SET status = ?, completed_at = ?
            WHERE actor_id = ? AND idempotency_key = ?
            """;
        jdbcTemplate.update(
            sql,
            IdempotencyStatus.FAILED.name(),
            Timestamp.from(Instant.now()),
            record.actorId(),
            record.idempotencyKey()
        );
    }

    private static class RecordRowMapper implements RowMapper<CommandIdempotencyRecord> {
        @Override
        public CommandIdempotencyRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            UUID idempotencyId = rs.getObject("idempotency_id", UUID.class);
            String actorId = rs.getString("actor_id");
            String idempotencyKey = rs.getString("idempotency_key");
            String requestHash = rs.getString("request_hash");
            String commandType = rs.getString("command_type");
            UUID aggregateId = rs.getObject("aggregate_id", UUID.class);
            IdempotencyStatus status = IdempotencyStatus.valueOf(rs.getString("status"));

            int respStatus = rs.getInt("response_status");
            Integer responseStatus = rs.wasNull() ? null : respStatus;
            String responsePayload = rs.getString("response_payload");

            Timestamp createdTs = rs.getTimestamp("created_at");
            Instant createdAt = createdTs.toInstant();

            Timestamp completedTs = rs.getTimestamp("completed_at");
            Instant completedAt = completedTs != null ? completedTs.toInstant() : null;

            Timestamp expiresTs = rs.getTimestamp("expires_at");
            Instant expiresAt = expiresTs != null ? expiresTs.toInstant() : null;

            return new CommandIdempotencyRecord(
                idempotencyId, actorId, idempotencyKey, requestHash, commandType,
                aggregateId, status, responseStatus, responsePayload, createdAt, completedAt, expiresAt
            );
        }
    }
}
