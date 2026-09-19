package com.chronos.infrastructure.persistence.postgres;

import com.chronos.application.port.SecurityAuditRepository;
import com.chronos.domain.security.audit.SecurityAuditAction;
import com.chronos.domain.security.audit.SecurityAuditRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.*;

@Repository
public class PostgresSecurityAuditRepository implements SecurityAuditRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<SecurityAuditRecord> ROW_MAPPER = (rs, rowNum) -> new SecurityAuditRecord(
        rs.getObject("audit_id", UUID.class),
        rs.getTimestamp("occurred_at").toInstant(),
        rs.getObject("actor_user_id", UUID.class),
        rs.getString("actor_username"),
        SecurityAuditAction.valueOf(rs.getString("action")),
        rs.getString("resource_type"),
        rs.getString("resource_id"),
        rs.getString("outcome"),
        rs.getString("correlation_id"),
        rs.getString("details")
    );

    public PostgresSecurityAuditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    @Override
    public void append(SecurityAuditRecord record) {
        Objects.requireNonNull(record, "record must not be null");

        String sql = """
            INSERT INTO security_audit_log (
                audit_id, occurred_at, actor_user_id, actor_username,
                action, resource_type, resource_id, outcome, correlation_id, details
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            """;

        jdbcTemplate.update(
            sql,
            record.auditId(),
            Timestamp.from(record.occurredAt()),
            record.actorUserId(),
            record.actorUsername(),
            record.action().name(),
            record.resourceType(),
            record.resourceId(),
            record.outcome(),
            record.correlationId(),
            record.detailsJson()
        );
    }

    @Override
    public List<SecurityAuditRecord> findPaged(int limit, int offset, String action, String actorUsername) {
        int boundedLimit = Math.clamp(limit, 1, 100);
        int boundedOffset = Math.max(offset, 0);

        StringBuilder sql = new StringBuilder("""
            SELECT audit_id, occurred_at, actor_user_id, actor_username,
                   action, resource_type, resource_id, outcome, correlation_id, details
            FROM security_audit_log
            WHERE 1=1
            """);

        List<Object> params = new ArrayList<>();

        if (action != null && !action.isBlank()) {
            sql.append(" AND action = ?");
            params.add(action.trim());
        }

        if (actorUsername != null && !actorUsername.isBlank()) {
            sql.append(" AND actor_username = ?");
            params.add(actorUsername.trim());
        }

        sql.append(" ORDER BY occurred_at DESC LIMIT ? OFFSET ?");
        params.add(boundedLimit);
        params.add(boundedOffset);

        return jdbcTemplate.query(sql.toString(), ROW_MAPPER, params.toArray());
    }

    @Override
    public long count() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM security_audit_log", Long.class);
        return count != null ? count : 0L;
    }
}
