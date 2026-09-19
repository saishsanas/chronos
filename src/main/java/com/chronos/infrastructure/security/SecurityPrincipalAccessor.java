package com.chronos.infrastructure.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class SecurityPrincipalAccessor {

    public Optional<SecurityPrincipal> getPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return Optional.empty();
        }

        if (auth.getPrincipal() instanceof Jwt jwt) {
            String sub = jwt.getSubject();
            UUID userId;
            try {
                userId = UUID.fromString(sub);
            } catch (Exception e) {
                userId = UUID.nameUUIDFromBytes(sub.getBytes(StandardCharsets.UTF_8));
            }

            String username = jwt.getClaimAsString("username");
            if (username == null || username.isBlank()) {
                username = jwt.getSubject();
            }

            List<String> rolesList = jwt.getClaimAsStringList("roles");
            Set<String> roles;
            if (rolesList != null) {
                roles = new HashSet<>(rolesList);
            } else {
                roles = auth.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .map(a -> a.startsWith("ROLE_") ? a.substring(5) : a)
                    .collect(Collectors.toSet());
            }

            return Optional.of(new SecurityPrincipal(userId, username, roles));
        }

        // Support for standard Spring Authentication (e.g. @WithMockUser in tests)
        String name = auth.getName();
        UUID userId = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
        Set<String> roles = auth.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .map(a -> a.startsWith("ROLE_") ? a.substring(5) : a)
            .collect(Collectors.toSet());

        return Optional.of(new SecurityPrincipal(userId, name, roles));
    }

    public SecurityPrincipal getRequiredPrincipal() {
        return getPrincipal().orElseThrow(() -> new IllegalStateException("No authenticated security principal in SecurityContext"));
    }
}
