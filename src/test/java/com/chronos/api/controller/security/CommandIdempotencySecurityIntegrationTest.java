package com.chronos.api.controller.security;

import com.chronos.TestDatabaseHelper;
import com.chronos.api.dto.CreateAccountRequest;
import com.chronos.application.port.EventStore;
import com.chronos.application.service.DevelopmentUserBootstrap;
import com.chronos.domain.event.DomainEventEnvelope;
import com.chronos.domain.security.ApplicationUser;
import com.chronos.domain.security.UserRole;
import com.chronos.infrastructure.security.JwtTokenService;
import com.chronos.application.port.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class CommandIdempotencySecurityIntegrationTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        TestDatabaseHelper.configureProperties(registry);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EventStore eventStore;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private DevelopmentUserBootstrap bootstrap;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ApplicationUser operator1;
    private ApplicationUser operator2;
    private String operator1Token;
    private String operator2Token;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE command_idempotency, event_store, outbox_events, inbox_events, account_summary_projection CASCADE");
        bootstrap.run();

        operator1 = userRepository.findByUsername("operator").orElseThrow();

        // Create second operator to test actor separation
        UUID op2Id = UUID.randomUUID();
        operator2 = new ApplicationUser(
            op2Id,
            "operator2_" + op2Id.toString().substring(0, 8),
            passwordEncoder.encode("SecretPassword123!"),
            true,
            Set.of(UserRole.OPERATOR),
            Instant.now(),
            Instant.now()
        );
        userRepository.save(operator2);

        operator1Token = jwtTokenService.generateToken(operator1);
        operator2Token = jwtTokenService.generateToken(operator2);
    }

    @Test
    @DisplayName("18. Authenticated actor userId binds to actorId in EventMetadata and idempotency record")
    void authenticatedActorBindsToEventMetadata() throws Exception {
        CreateAccountRequest req = new CreateAccountRequest("USD", 1000L, 500L);
        String idempotencyKey = "key-bind-" + UUID.randomUUID();

        MvcResult res = mockMvc.perform(post("/api/v1/accounts")
                .header("Authorization", "Bearer " + operator1Token)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andReturn();

        UUID accountId = UUID.fromString(
            objectMapper.readTree(res.getResponse().getContentAsString()).get("aggregateId").asText()
        );

        List<DomainEventEnvelope> events = eventStore.loadStream(accountId);
        assertThat(events).hasSize(1);
        // Verify actorId matches operator1 userId UUID string
        assertThat(events.get(0).metadata().actorId()).isEqualTo(operator1.userId().toString());
    }

    @Test
    @DisplayName("19. Same authenticated actor + same key + same payload returns cached logical response")
    void sameActorSameKeySamePayload() throws Exception {
        CreateAccountRequest req = new CreateAccountRequest("USD", 1000L, 500L);
        String idempotencyKey = "key-cache-" + UUID.randomUUID();

        MvcResult res1 = mockMvc.perform(post("/api/v1/accounts")
                .header("Authorization", "Bearer " + operator1Token)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andReturn();

        UUID accountId1 = UUID.fromString(
            objectMapper.readTree(res1.getResponse().getContentAsString()).get("aggregateId").asText()
        );

        // Second request with same actor, key, and payload
        MvcResult res2 = mockMvc.perform(post("/api/v1/accounts")
                .header("Authorization", "Bearer " + operator1Token)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andReturn();

        UUID accountId2 = UUID.fromString(
            objectMapper.readTree(res2.getResponse().getContentAsString()).get("aggregateId").asText()
        );

        assertThat(accountId1).isEqualTo(accountId2);
        // Ensure only 1 aggregate stream exists
        List<DomainEventEnvelope> events = eventStore.loadStream(accountId1);
        assertThat(events).hasSize(1);
    }

    @Test
    @DisplayName("20. Same authenticated actor + same key + different payload returns HTTP 409 Conflict")
    void sameActorSameKeyDifferentPayloadConflict() throws Exception {
        CreateAccountRequest req1 = new CreateAccountRequest("USD", 1000L, 500L);
        CreateAccountRequest req2 = new CreateAccountRequest("EUR", 2000L, 800L);
        String idempotencyKey = "key-conflict-" + UUID.randomUUID();

        mockMvc.perform(post("/api/v1/accounts")
                .header("Authorization", "Bearer " + operator1Token)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req1)))
            .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/accounts")
                .header("Authorization", "Bearer " + operator1Token)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req2)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.status").value(409))
            .andExpect(jsonPath("$.errorCode").value("COMMAND_IDEMPOTENCY_CONFLICT"));
    }

    @Test
    @DisplayName("21. Different authenticated actors using same idempotency key do not collide")
    void differentActorsDoNotCollide() throws Exception {
        CreateAccountRequest req1 = new CreateAccountRequest("USD", 1000L, 500L);
        CreateAccountRequest req2 = new CreateAccountRequest("USD", 1000L, 500L);
        String sharedKey = "shared-key-" + UUID.randomUUID();

        // Operator 1 executes
        MvcResult res1 = mockMvc.perform(post("/api/v1/accounts")
                .header("Authorization", "Bearer " + operator1Token)
                .header("Idempotency-Key", sharedKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req1)))
            .andExpect(status().isCreated())
            .andReturn();

        // Operator 2 executes with the same key
        MvcResult res2 = mockMvc.perform(post("/api/v1/accounts")
                .header("Authorization", "Bearer " + operator2Token)
                .header("Idempotency-Key", sharedKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req2)))
            .andExpect(status().isCreated())
            .andReturn();

        UUID id1 = UUID.fromString(objectMapper.readTree(res1.getResponse().getContentAsString()).get("aggregateId").asText());
        UUID id2 = UUID.fromString(objectMapper.readTree(res2.getResponse().getContentAsString()).get("aggregateId").asText());

        // Each actor gets their own account without collision
        assertThat(id1).isNotEqualTo(id2);
        assertThat(eventStore.loadStream(id1)).hasSize(1);
        assertThat(eventStore.loadStream(id2)).hasSize(1);
    }
}
