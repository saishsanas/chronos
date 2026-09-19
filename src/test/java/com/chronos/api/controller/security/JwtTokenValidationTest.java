package com.chronos.api.controller.security;

import com.chronos.TestDatabaseHelper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class JwtTokenValidationTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        TestDatabaseHelper.configureProperties(registry);
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("5. Expired JWT token is rejected with HTTP 401")
    void expiredTokenRejected() throws Exception {
        String expiredToken = createSignedToken(
            TestDatabaseHelper.TEST_JWT_SECRET,
            Instant.now().minusSeconds(3600),
            Instant.now().minusSeconds(60),
            UUID.randomUUID().toString(),
            "expired_user",
            List.of("OPERATOR")
        );

        mockMvc.perform(get("/api/v1/accounts/" + UUID.randomUUID())
                .header("Authorization", "Bearer " + expiredToken))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("6. Malformed JWT token is rejected with HTTP 401")
    void malformedTokenRejected() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/" + UUID.randomUUID())
                .header("Authorization", "Bearer not.a.valid.jwt.payload"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("7. Token signed with wrong secret is rejected with HTTP 401")
    void invalidSignatureRejected() throws Exception {
        String wrongSecret = "completely-wrong-secret-that-does-not-match-at-all-32chars";
        String invalidSigToken = createSignedToken(
            wrongSecret,
            Instant.now(),
            Instant.now().plusSeconds(3600),
            UUID.randomUUID().toString(),
            "bad_signature_user",
            List.of("OPERATOR")
        );

        mockMvc.perform(get("/api/v1/accounts/" + UUID.randomUUID())
                .header("Authorization", "Bearer " + invalidSigToken))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.status").value(401));
    }

    private String createSignedToken(
        String secret,
        Instant issuedAt,
        Instant expiresAt,
        String subject,
        String username,
        List<String> roles
    ) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .issuer("chronos-engine")
            .subject(subject)
            .claim("username", username)
            .claim("roles", roles)
            .issueTime(Date.from(issuedAt))
            .expirationTime(Date.from(expiresAt))
            .build();

        SignedJWT signedJWT = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        signedJWT.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
        return signedJWT.serialize();
    }
}
