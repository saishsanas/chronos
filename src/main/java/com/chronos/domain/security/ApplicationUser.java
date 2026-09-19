package com.chronos.domain.security;

import java.time.Instant;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record ApplicationUser(
    UUID userId,
    String username,
    String passwordHash,
    boolean enabled,
    Set<UserRole> roles,
    Instant createdAt,
    Instant updatedAt
) {
    public ApplicationUser {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(username, "username must not be null");
        Objects.requireNonNull(passwordHash, "passwordHash must not be null");
        Objects.requireNonNull(roles, "roles must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        roles = Collections.unmodifiableSet(roles);
    }

    public static ApplicationUser create(String username, String passwordHash, Set<UserRole> roles) {
        Instant now = Instant.now();
        return new ApplicationUser(UUID.randomUUID(), username, passwordHash, true, roles, now, now);
    }

    public static ApplicationUser createWithId(UUID userId, String username, String passwordHash, Set<UserRole> roles) {
        Instant now = Instant.now();
        return new ApplicationUser(userId, username, passwordHash, true, roles, now, now);
    }

    public boolean hasRole(UserRole role) {
        return roles != null && roles.contains(role);
    }
}
