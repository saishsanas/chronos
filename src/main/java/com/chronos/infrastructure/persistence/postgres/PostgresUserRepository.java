package com.chronos.infrastructure.persistence.postgres;

import com.chronos.application.port.UserRepository;
import com.chronos.domain.security.ApplicationUser;
import com.chronos.domain.security.UserRole;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Repository
public class PostgresUserRepository implements UserRepository {

    private final JdbcTemplate jdbcTemplate;

    public PostgresUserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    @Override
    public Optional<ApplicationUser> findByUsername(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        String sql = """
            SELECT user_id, username, password_hash, enabled, created_at, updated_at
            FROM application_users
            WHERE username = ?
            """;
        try {
            ApplicationUser user = jdbcTemplate.queryForObject(sql, (rs, rowNum) -> mapUserWithoutRoles(rs), username);
            if (user == null) {
                return Optional.empty();
            }
            Set<UserRole> roles = loadRolesForUser(user.userId());
            return Optional.of(new ApplicationUser(
                user.userId(),
                user.username(),
                user.passwordHash(),
                user.enabled(),
                roles,
                user.createdAt(),
                user.updatedAt()
            ));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<ApplicationUser> findById(UUID userId) {
        if (userId == null) {
            return Optional.empty();
        }
        String sql = """
            SELECT user_id, username, password_hash, enabled, created_at, updated_at
            FROM application_users
            WHERE user_id = ?
            """;
        try {
            ApplicationUser user = jdbcTemplate.queryForObject(sql, (rs, rowNum) -> mapUserWithoutRoles(rs), userId);
            if (user == null) {
                return Optional.empty();
            }
            Set<UserRole> roles = loadRolesForUser(user.userId());
            return Optional.of(new ApplicationUser(
                user.userId(),
                user.username(),
                user.passwordHash(),
                user.enabled(),
                roles,
                user.createdAt(),
                user.updatedAt()
            ));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    @Transactional
    public void save(ApplicationUser user) {
        Objects.requireNonNull(user, "user must not be null");

        String upsertUserSql = """
            INSERT INTO application_users (user_id, username, password_hash, enabled, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (user_id) DO UPDATE SET
                username = EXCLUDED.username,
                password_hash = EXCLUDED.password_hash,
                enabled = EXCLUDED.enabled,
                updated_at = EXCLUDED.updated_at
            """;

        jdbcTemplate.update(
            upsertUserSql,
            user.userId(),
            user.username(),
            user.passwordHash(),
            user.enabled(),
            Timestamp.from(user.createdAt()),
            Timestamp.from(user.updatedAt())
        );

        // Delete existing roles and insert current roles
        jdbcTemplate.update("DELETE FROM user_roles WHERE user_id = ?", user.userId());

        String insertRoleSql = "INSERT INTO user_roles (user_id, role) VALUES (?, ?) ON CONFLICT DO NOTHING";
        for (UserRole role : user.roles()) {
            jdbcTemplate.update(insertRoleSql, user.userId(), role.name());
        }
    }

    @Override
    public long count() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM application_users", Long.class);
        return count != null ? count : 0L;
    }

    private Set<UserRole> loadRolesForUser(UUID userId) {
        String sql = "SELECT role FROM user_roles WHERE user_id = ?";
        List<String> roleStrings = jdbcTemplate.query(sql, (rs, rowNum) -> rs.getString("role"), userId);
        Set<UserRole> roles = new HashSet<>();
        for (String r : roleStrings) {
            try {
                roles.add(UserRole.valueOf(r));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return roles;
    }

    private ApplicationUser mapUserWithoutRoles(ResultSet rs) throws SQLException {
        return new ApplicationUser(
            rs.getObject("user_id", UUID.class),
            rs.getString("username"),
            rs.getString("password_hash"),
            rs.getBoolean("enabled"),
            Collections.emptySet(),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
        );
    }
}
