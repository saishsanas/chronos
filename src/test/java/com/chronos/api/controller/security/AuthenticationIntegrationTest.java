package com.chronos.api.controller.security;

import com.chronos.TestDatabaseHelper;
import com.chronos.api.dto.LoginRequest;
import com.chronos.application.port.UserRepository;
import com.chronos.application.service.DevelopmentUserBootstrap;
import com.chronos.domain.security.ApplicationUser;
import com.chronos.domain.security.UserRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class AuthenticationIntegrationTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        TestDatabaseHelper.configureProperties(registry);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private DevelopmentUserBootstrap bootstrap;

    @BeforeEach
    void setUp() {
        bootstrap.run();
    }

    @Test
    @DisplayName("1. Valid admin login returns HTTP 200 with JWT access token and claims")
    void validAdminLogin() throws Exception {
        LoginRequest req = new LoginRequest("admin", DevelopmentUserBootstrap.DEV_ADMIN_PASSWORD);

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req))
                .header("X-Correlation-Id", UUID.randomUUID().toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").isString())
            .andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.username").value("admin"))
            .andExpect(jsonPath("$.userId").value(DevelopmentUserBootstrap.ADMIN_USER_ID.toString()))
            .andExpect(jsonPath("$.roles[0]").value("ADMIN"))
            .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.has("password")).isFalse();
        assertThat(json.has("passwordHash")).isFalse();
    }

    @Test
    @DisplayName("2. Invalid password returns HTTP 401 with standard error shape")
    void invalidPasswordReturns401() throws Exception {
        LoginRequest req = new LoginRequest("admin", "WrongPassword999!");

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.status").value(401))
            .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.message").value("Invalid username or password"));
    }

    @Test
    @DisplayName("3. Unknown username returns HTTP 401 without revealing existence")
    void unknownUsernameReturns401() throws Exception {
        LoginRequest req = new LoginRequest("nonexistent_user", "AnyPassword123!");

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.status").value(401))
            .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.message").value("Invalid username or password"));
    }

    @Test
    @DisplayName("4. Disabled user cannot authenticate (HTTP 401)")
    void disabledUserCannotAuthenticate() throws Exception {
        String username = "disabled_user_" + UUID.randomUUID();
        ApplicationUser disabledUser = new ApplicationUser(
            UUID.randomUUID(),
            username,
            passwordEncoder.encode("SecretPass123!"),
            false,
            Set.of(UserRole.OPERATOR),
            java.time.Instant.now(),
            java.time.Instant.now()
        );
        userRepository.save(disabledUser);

        LoginRequest req = new LoginRequest(username, "SecretPass123!");

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.status").value(401))
            .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
    }
}
