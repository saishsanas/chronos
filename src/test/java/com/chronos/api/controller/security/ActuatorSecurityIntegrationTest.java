package com.chronos.api.controller.security;

import com.chronos.TestDatabaseHelper;
import com.chronos.application.port.UserRepository;
import com.chronos.application.service.DevelopmentUserBootstrap;
import com.chronos.domain.security.ApplicationUser;
import com.chronos.infrastructure.security.JwtTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class ActuatorSecurityIntegrationTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        TestDatabaseHelper.configureProperties(registry);
    }

    @Autowired
    private MockMvc mockMvc;

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
    @DisplayName("Health endpoint is public to unauthenticated callers")
    void healthEndpointIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").exists());
    }

    @Test
    @DisplayName("Sensitive actuator metrics endpoint rejects unauthenticated access (HTTP 401)")
    void unauthenticatedMetricsReturns401() throws Exception {
        mockMvc.perform(get("/actuator/metrics"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("Sensitive actuator metrics endpoint rejects OPERATOR access (HTTP 403)")
    void operatorMetricsReturns403() throws Exception {
        mockMvc.perform(get("/actuator/metrics")
                .header("Authorization", "Bearer " + operatorToken))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("Sensitive actuator metrics endpoint rejects AUDITOR access (HTTP 403)")
    void auditorMetricsReturns403() throws Exception {
        mockMvc.perform(get("/actuator/metrics")
                .header("Authorization", "Bearer " + auditorToken))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("Sensitive actuator metrics endpoint permits ADMIN access (HTTP 200)")
    void adminMetricsReturns200() throws Exception {
        mockMvc.perform(get("/actuator/metrics")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.names").isArray());
    }
}
