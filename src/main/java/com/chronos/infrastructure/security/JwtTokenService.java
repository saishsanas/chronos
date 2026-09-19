package com.chronos.infrastructure.security;

import com.chronos.domain.security.ApplicationUser;
import com.chronos.domain.security.UserRole;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class JwtTokenService {

    private final JwtEncoder jwtEncoder;
    private final long expirationSeconds;

    public JwtTokenService(
        JwtEncoder jwtEncoder,
        @Value("${chronos.security.jwt.expiration-seconds:3600}") long expirationSeconds
    ) {
        this.jwtEncoder = Objects.requireNonNull(jwtEncoder, "jwtEncoder must not be null");
        this.expirationSeconds = expirationSeconds > 0 ? expirationSeconds : 3600;
    }

    public String generateToken(ApplicationUser user) {
        Objects.requireNonNull(user, "user must not be null");

        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(expirationSeconds);

        List<String> roles = user.roles().stream()
            .map(UserRole::name)
            .toList();

        JwtClaimsSet claims = JwtClaimsSet.builder()
            .issuer("chronos-engine")
            .issuedAt(now)
            .expiresAt(expiresAt)
            .subject(user.userId().toString())
            .claim("username", user.username())
            .claim("roles", roles)
            .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public long getExpirationSeconds() {
        return expirationSeconds;
    }
}
