package com.chronos.infrastructure.persistence.postgres;

import com.chronos.application.port.ProjectionRebuildJobRepository;
import com.chronos.domain.projection.ProjectionRebuildJob;
import com.chronos.domain.projection.RebuildScope;
import com.chronos.domain.projection.RebuildStatus;
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
public class PostgresProjectionRebuildJobRepository implements ProjectionRebuildJobRepository {

    private final JdbcTemplate jdbcTemplate;

    public PostgresProjectionRebuildJobRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    @Override
    @Transactional
    public void save(ProjectionRebuildJob job) {
        Objects.requireNonNull(job, "job must not be null");
        String sql = """
            INSERT INTO projection_rebuild_jobs (
                job_id, projection_name, scope, target_account_id, status,
                rebuild_generation, requested_at, started_at, completed_at,
                events_processed, resulting_sequence, error_details
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (job_id)
            DO UPDATE SET
                status = EXCLUDED.status,
                started_at = EXCLUDED.started_at,
                completed_at = EXCLUDED.completed_at,
                events_processed = EXCLUDED.events_processed,
                resulting_sequence = EXCLUDED.resulting_sequence,
                error_details = EXCLUDED.error_details
            """;

        jdbcTemplate.update(
            sql,
            job.jobId(),
            job.projectionName(),
            job.scope().name(),
            job.targetAccountId(),
            job.status().name(),
            job.rebuildGeneration(),
            Timestamp.from(job.requestedAt()),
            job.startedAt() != null ? Timestamp.from(job.startedAt()) : null,
            job.completedAt() != null ? Timestamp.from(job.completedAt()) : null,
            job.eventsProcessed(),
            job.resultingSequence(),
            job.errorDetails()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProjectionRebuildJob> findById(UUID jobId) {
        Objects.requireNonNull(jobId, "jobId must not be null");
        String sql = "SELECT * FROM projection_rebuild_jobs WHERE job_id = ?";
        try {
            ProjectionRebuildJob job = jdbcTemplate.queryForObject(sql, new JobRowMapper(), jobId);
            return Optional.ofNullable(job);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProjectionRebuildJob> findLatestForProjection(String projectionName) {
        Objects.requireNonNull(projectionName, "projectionName must not be null");
        String sql = "SELECT * FROM projection_rebuild_jobs WHERE projection_name = ? ORDER BY requested_at DESC LIMIT 1";
        try {
            ProjectionRebuildJob job = jdbcTemplate.queryForObject(sql, new JobRowMapper(), projectionName);
            return Optional.ofNullable(job);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    @Transactional
    public void markStarted(UUID jobId) {
        String sql = "UPDATE projection_rebuild_jobs SET status = ?, started_at = ? WHERE job_id = ?";
        jdbcTemplate.update(sql, RebuildStatus.RUNNING.name(), Timestamp.from(Instant.now()), jobId);
    }

    @Override
    @Transactional
    public void markSucceeded(UUID jobId, long eventsProcessed, long resultingSequence) {
        String sql = "UPDATE projection_rebuild_jobs SET status = ?, completed_at = ?, events_processed = ?, resulting_sequence = ? WHERE job_id = ?";
        jdbcTemplate.update(sql, RebuildStatus.SUCCEEDED.name(), Timestamp.from(Instant.now()), eventsProcessed, resultingSequence, jobId);
    }

    @Override
    @Transactional
    public void markFailed(UUID jobId, String errorDetails) {
        String sql = "UPDATE projection_rebuild_jobs SET status = ?, completed_at = ?, error_details = ? WHERE job_id = ?";
        jdbcTemplate.update(sql, RebuildStatus.FAILED.name(), Timestamp.from(Instant.now()), errorDetails, jobId);
    }

    private static class JobRowMapper implements RowMapper<ProjectionRebuildJob> {
        @Override
        public ProjectionRebuildJob mapRow(ResultSet rs, int rowNum) throws SQLException {
            UUID jobId = rs.getObject("job_id", UUID.class);
            String projectionName = rs.getString("projection_name");
            RebuildScope scope = RebuildScope.valueOf(rs.getString("scope"));
            UUID targetAccountId = rs.getObject("target_account_id", UUID.class);
            RebuildStatus status = RebuildStatus.valueOf(rs.getString("status"));
            long rebuildGeneration = rs.getLong("rebuild_generation");

            Timestamp reqTs = rs.getTimestamp("requested_at");
            Instant requestedAt = reqTs.toInstant();

            Timestamp startTs = rs.getTimestamp("started_at");
            Instant startedAt = startTs != null ? startTs.toInstant() : null;

            Timestamp compTs = rs.getTimestamp("completed_at");
            Instant completedAt = compTs != null ? compTs.toInstant() : null;

            long eventsProcessed = rs.getLong("events_processed");
            long resultingSequence = rs.getLong("resulting_sequence");
            String errorDetails = rs.getString("error_details");

            return new ProjectionRebuildJob(
                jobId, projectionName, scope, targetAccountId, status,
                rebuildGeneration, requestedAt, startedAt, completedAt,
                eventsProcessed, resultingSequence, errorDetails
            );
        }
    }
}
