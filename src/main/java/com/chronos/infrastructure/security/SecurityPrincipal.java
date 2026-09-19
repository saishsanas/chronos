package com.chronos.infrastructure.security;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record SecurityPrincipal(
    UUID userId,
    String username,
    Set<String> roles
) {
    public SecurityPrincipal {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(username, "username must not be null");
        Objects.requireNonNull(roles, "roles must not be null");
        roles = Collections.unmodifiableSet(roles);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public static SecurityPrincipal anonymous() {
        return new SecurityPrincipal(
            UUID.fromString("00000000-0000-0000-0000-000000000000"),
            "anonymous",
            Collections.emptySet()
        );
    }
}
