package com.chronos.infrastructure.persistence.postgres;

import com.chronos.application.port.AccountSummaryProjectionRepository;
import com.chronos.domain.account.AccountStatus;
import com.chronos.domain.projection.AccountSummaryProjection;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PostgresAccountSummaryProjectionRepository implements AccountSummaryProjectionRepository {

    private final JdbcTemplate jdbcTemplate;

    public PostgresAccountSummaryProjectionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AccountSummaryProjection> findByAccountId(UUID accountId) {
        Objects.requireNonNull(accountId, "accountId must not be null");
        String sql = """
            SELECT account_id, currency, balance_minor, overdraft_limit_minor, transaction_limit_minor,
                   status, sequence_number, last_updated_at, projected_at
            FROM account_summary_projection
            WHERE account_id = ?
            """;
        try {
            AccountSummaryProjection projection = jdbcTemplate.queryForObject(sql, new ProjectionRowMapper(), accountId);
            return Optional.ofNullable(projection);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccountSummaryProjection> findAll() {
        String sql = """
            SELECT account_id, currency, balance_minor, overdraft_limit_minor, transaction_limit_minor,
                   status, sequence_number, last_updated_at, projected_at
            FROM account_summary_projection
            ORDER BY account_id
            """;
        return jdbcTemplate.query(sql, new ProjectionRowMapper());
    }

    @Override
    @Transactional
    public void save(AccountSummaryProjection projection) {
        Objects.requireNonNull(projection, "projection must not be null");
        String sql = """
            INSERT INTO account_summary_projection (
                account_id, currency, balance_minor, overdraft_limit_minor, transaction_limit_minor,
                status, sequence_number, last_updated_at, projected_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (account_id)
            DO UPDATE SET
                currency = EXCLUDED.currency,
                balance_minor = EXCLUDED.balance_minor,
                overdraft_limit_minor = EXCLUDED.overdraft_limit_minor,
                transaction_limit_minor = EXCLUDED.transaction_limit_minor,
                status = EXCLUDED.status,
                sequence_number = EXCLUDED.sequence_number,
                last_updated_at = EXCLUDED.last_updated_at,
                projected_at = EXCLUDED.projected_at
            """;

        jdbcTemplate.update(
            sql,
            projection.accountId(),
            projection.currency(),
            projection.balanceMinor(),
            projection.overdraftLimitMinor(),
            projection.transactionLimitMinor(),
            projection.status().name(),
            projection.sequenceNumber(),
            Timestamp.from(projection.lastUpdatedAt()),
            Timestamp.from(projection.projectedAt())
        );
    }

    @Override
    @Transactional
    public void deleteByAccountId(UUID accountId) {
        Objects.requireNonNull(accountId, "accountId must not be null");
        jdbcTemplate.update("DELETE FROM account_summary_projection WHERE account_id = ?", accountId);
    }

    @Override
    @Transactional
    public void prepareStagingTable() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS account_summary_projection_staging");
        jdbcTemplate.execute("""
            CREATE TABLE account_summary_projection_staging (
                account_id UUID PRIMARY KEY,
                currency VARCHAR(3) NOT NULL,
                balance_minor BIGINT NOT NULL,
                overdraft_limit_minor BIGINT NOT NULL,
                transaction_limit_minor BIGINT NOT NULL,
                status VARCHAR(20) NOT NULL,
                sequence_number BIGINT NOT NULL,
                last_updated_at TIMESTAMPTZ NOT NULL,
                projected_at TIMESTAMPTZ NOT NULL
            )
            """);
    }

    @Override
    @Transactional
    public void saveToStaging(List<AccountSummaryProjection> projections) {
        if (projections == null || projections.isEmpty()) return;
        String sql = """
            INSERT INTO account_summary_projection_staging (
                account_id, currency, balance_minor, overdraft_limit_minor, transaction_limit_minor,
                status, sequence_number, last_updated_at, projected_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (account_id)
            DO UPDATE SET
                currency = EXCLUDED.currency,
                balance_minor = EXCLUDED.balance_minor,
                overdraft_limit_minor = EXCLUDED.overdraft_limit_minor,
                transaction_limit_minor = EXCLUDED.transaction_limit_minor,
                status = EXCLUDED.status,
                sequence_number = EXCLUDED.sequence_number,
                last_updated_at = EXCLUDED.last_updated_at,
                projected_at = EXCLUDED.projected_at
            """;

        jdbcTemplate.batchUpdate(sql, projections, projections.size(), (PreparedStatement ps, AccountSummaryProjection p) -> {
            ps.setObject(1, p.accountId());
            ps.setString(2, p.currency());
            ps.setLong(3, p.balanceMinor());
            ps.setLong(4, p.overdraftLimitMinor());
            ps.setLong(5, p.transactionLimitMinor());
            ps.setString(6, p.status().name());
            ps.setLong(7, p.sequenceNumber());
            ps.setTimestamp(8, Timestamp.from(p.lastUpdatedAt()));
            ps.setTimestamp(9, Timestamp.from(p.projectedAt()));
        });
    }

    @Override
    @Transactional
    public void swapStagingToLive() {
        // Atomic cutover: clear live table and insert all staged rows in single transaction
        jdbcTemplate.execute("TRUNCATE TABLE account_summary_projection");
        jdbcTemplate.execute("INSERT INTO account_summary_projection SELECT * FROM account_summary_projection_staging");
        jdbcTemplate.execute("DROP TABLE IF EXISTS account_summary_projection_staging");
    }

    private static class ProjectionRowMapper implements RowMapper<AccountSummaryProjection> {
        @Override
        public AccountSummaryProjection mapRow(ResultSet rs, int rowNum) throws SQLException {
            UUID accountId = rs.getObject("account_id", UUID.class);
            String currency = rs.getString("currency");
            long balanceMinor = rs.getLong("balance_minor");
            long overdraftLimitMinor = rs.getLong("overdraft_limit_minor");
            long transactionLimitMinor = rs.getLong("transaction_limit_minor");
            AccountStatus status = AccountStatus.valueOf(rs.getString("status"));
            long sequenceNumber = rs.getLong("sequence_number");

            Timestamp lastUpdatedTs = rs.getTimestamp("last_updated_at");
            Instant lastUpdatedAt = lastUpdatedTs.toInstant();

            Timestamp projectedTs = rs.getTimestamp("projected_at");
            Instant projectedAt = projectedTs.toInstant();

            return new AccountSummaryProjection(
                accountId, currency, balanceMinor, overdraftLimitMinor, transactionLimitMinor,
                status, sequenceNumber, lastUpdatedAt, projectedAt
            );
        }
    }
}
