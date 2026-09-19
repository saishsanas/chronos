package com.chronos.api.controller.security;

import com.chronos.TestDatabaseHelper;
import com.chronos.api.dto.CreateAccountRequest;
import com.chronos.application.service.DevelopmentUserBootstrap;
import com.chronos.infrastructure.security.JwtTokenService;
import com.chronos.application.port.UserRepository;
import com.chronos.domain.security.ApplicationUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class AuthorizationRbacIntegrationTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        TestDatabaseHelper.configureProperties(registry);
        registry.add("chronos.ops.projection-rebuild.enabled", () -> "true");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private DevelopmentUserBootstrap bootstrap;

    private String adminToken;
    private String operatorToken;
    private String auditorToken;

    @BeforeEach
    void setUp() {
        bootstrap.run();
        ApplicationUser admin = userRepository.findByUsername("admin").orElseThrow();
        ApplicationUser operator = userRepository.findByUsername("operator").orElseThrow();
        ApplicationUser auditor = userRepository.findByUsername("auditor").orElseThrow();

        adminToken = jwtTokenService.generateToken(admin);
        operatorToken = jwtTokenService.generateToken(operator);
        auditorToken = jwtTokenService.generateToken(auditor);
    }

    @Test
    @DisplayName("9. Unauthenticated read request returns HTTP 401")
    void unauthenticatedReadReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/" + UUID.randomUUID()))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("10. Unauthenticated write request returns HTTP 401")
    void unauthenticatedWriteReturns401() throws Exception {
        CreateAccountRequest req = new CreateAccountRequest("USD", 1000L, 500L);

        mockMvc.perform(post("/api/v1/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("11. OPERATOR can perform permitted financial write command (HTTP 201)")
    void operatorCanPerformWriteCommand() throws Exception {
        CreateAccountRequest req = new CreateAccountRequest("USD", 1000L, 500L);

        mockMvc.perform(post("/api/v1/accounts")
                .header("Authorization", "Bearer " + operatorToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.aggregateId").isString());
    }

    @Test
    @DisplayName("12. AUDITOR cannot perform financial write command (HTTP 403 Forbidden)")
    void auditorCannotPerformWriteCommand() throws Exception {
        CreateAccountRequest req = new CreateAccountRequest("USD", 1000L, 500L);

        mockMvc.perform(post("/api/v1/accounts")
                .header("Authorization", "Bearer " + auditorToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.status").value(403))
            .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("13. ADMIN can execute projection rebuild")
    void adminCanExecuteProjectionRebuild() throws Exception {
        mockMvc.perform(post("/api/v1/ops/projections/rebuild")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.jobId").isString());
    }

    @Test
    @DisplayName("14. OPERATOR cannot execute projection rebuild (HTTP 403 Forbidden)")
    void operatorCannotExecuteProjectionRebuild() throws Exception {
        mockMvc.perform(post("/api/v1/ops/projections/rebuild")
                .header("Authorization", "Bearer " + operatorToken))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("15. AUDITOR can read account state and events (HTTP 200)")
    void auditorCanReadAccountStateAndEvents() throws Exception {
        // Create account via OPERATOR first
        CreateAccountRequest req = new CreateAccountRequest("USD", 1000L, 500L);
        MvcResult createResult = mockMvc.perform(post("/api/v1/accounts")
                .header("Authorization", "Bearer " + operatorToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andReturn();

        String accountId = objectMapper.readTree(createResult.getResponse().getContentAsString())
            .get("aggregateId").asText();

        // AUDITOR reads current state
        mockMvc.perform(get("/api/v1/accounts/" + accountId)
                .header("Authorization", "Bearer " + auditorToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accountId").value(accountId));

        // AUDITOR reads event history
        mockMvc.perform(get("/api/v1/accounts/" + accountId + "/events")
                .header("Authorization", "Bearer " + auditorToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].eventType").value("AccountCreated"));
    }
}
