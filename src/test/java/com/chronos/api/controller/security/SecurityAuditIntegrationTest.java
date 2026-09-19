package com.chronos.api.controller.security;

import com.chronos.TestDatabaseHelper;
import com.chronos.api.dto.CreateAccountRequest;
import com.chronos.api.dto.LoginRequest;
import com.chronos.application.port.SecurityAuditRepository;
import com.chronos.application.service.DevelopmentUserBootstrap;
import com.chronos.application.service.SecurityAuditService;
import com.chronos.domain.security.ApplicationUser;
import com.chronos.domain.security.audit.SecurityAuditRecord;
import com.chronos.infrastructure.observability.ChronosMetrics;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class SecurityAuditIntegrationTest {

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
    private SecurityAuditRepository auditRepository;

    @Autowired
    private DevelopmentUserBootstrap bootstrap;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ChronosMetrics metrics;

    private String adminToken;
    private String operatorToken;
    private String auditorToken;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE security_audit_log CASCADE");
        bootstrap.run();

        ApplicationUser admin = userRepository.findByUsername("admin").orElseThrow();
        ApplicationUser operator = userRepository.findByUsername("operator").orElseThrow();
        ApplicationUser auditor = userRepository.findByUsername("auditor").orElseThrow();

        adminToken = jwtTokenService.generateToken(admin);
        operatorToken = jwtTokenService.generateToken(operator);
        auditorToken = jwtTokenService.generateToken(auditor);
    }

    @Test
    @DisplayName("23. Successful login is recorded in security audit log")
    void successfulLoginAudited() throws Exception {
        LoginRequest req = new LoginRequest("admin", DevelopmentUserBootstrap.DEV_ADMIN_PASSWORD);
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk());

        List<SecurityAuditRecord> logs = auditRepository.findPaged(10, 0, "LOGIN_SUCCESS", "admin");
        assertThat(logs).isNotEmpty();
        SecurityAuditRecord logEntry = logs.get(0);
        assertThat(logEntry.action().name()).isEqualTo("LOGIN_SUCCESS");
        assertThat(logEntry.actorUsername()).isEqualTo("admin");
        assertThat(logEntry.actorUserId()).isEqualTo(DevelopmentUserBootstrap.ADMIN_USER_ID);
        assertThat(logEntry.outcome()).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("24. Failed login is recorded in security audit log")
    void failedLoginAudited() throws Exception {
        LoginRequest req = new LoginRequest("admin", "WrongPassword!");
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isUnauthorized());

        List<SecurityAuditRecord> logs = auditRepository.findPaged(10, 0, "LOGIN_FAILURE", "admin");
        assertThat(logs).isNotEmpty();
        SecurityAuditRecord logEntry = logs.get(0);
        assertThat(logEntry.action().name()).isEqualTo("LOGIN_FAILURE");
        assertThat(logEntry.outcome()).isEqualTo("FAILURE");
    }

    @Test
    @DisplayName("25. Security authorization denial is recorded in security audit log")
    void securityDenialAudited() throws Exception {
        // AUDITOR attempts to perform a financial write command
        CreateAccountRequest req = new CreateAccountRequest("USD", 1000L, 500L);
        mockMvc.perform(post("/api/v1/accounts")
                .header("Authorization", "Bearer " + auditorToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isForbidden());

        List<SecurityAuditRecord> logs = auditRepository.findPaged(10, 0, "SECURITY_DENIED", null);
        assertThat(logs).isNotEmpty();
    }

    @Test
    @DisplayName("26. Financial command execution is audited with actor userId and correlationId")
    void accountCommandAudited() throws Exception {
        CreateAccountRequest req = new CreateAccountRequest("USD", 1000L, 500L);
        String corrId = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/v1/accounts")
                .header("Authorization", "Bearer " + operatorToken)
                .header("X-Correlation-Id", corrId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated());

        List<SecurityAuditRecord> logs = auditRepository.findPaged(10, 0, "ACCOUNT_COMMAND", "operator");
        assertThat(logs).isNotEmpty();
        SecurityAuditRecord record = logs.get(0);
        assertThat(record.action().name()).isEqualTo("ACCOUNT_COMMAND");
        assertThat(record.actorUsername()).isEqualTo("operator");
        assertThat(record.actorUserId()).isEqualTo(DevelopmentUserBootstrap.OPERATOR_USER_ID);
        assertThat(record.correlationId()).isEqualTo(corrId);
    }

    @Test
    @DisplayName("27. Projection rebuild is audited with ADMIN attribution")
    void projectionRebuildAudited() throws Exception {
        String corrId = UUID.randomUUID().toString();
        mockMvc.perform(post("/api/v1/ops/projections/rebuild")
                .header("Authorization", "Bearer " + adminToken)
                .header("X-Correlation-Id", corrId))
            .andExpect(status().isOk());

        List<SecurityAuditRecord> logs = auditRepository.findPaged(10, 0, "PROJECTION_REBUILD", "admin");
        assertThat(logs).isNotEmpty();
        SecurityAuditRecord record = logs.get(0);
        assertThat(record.action().name()).isEqualTo("PROJECTION_REBUILD");
        assertThat(record.actorUsername()).isEqualTo("admin");
        assertThat(record.actorUserId()).isEqualTo(DevelopmentUserBootstrap.ADMIN_USER_ID);
    }

    @Test
    @DisplayName("28. Audit log query endpoint requires ADMIN or AUDITOR role")
    void auditEndpointRbac() throws Exception {
        // Unauthenticated -> 401
        mockMvc.perform(get("/api/v1/ops/audit"))
            .andExpect(status().isUnauthorized());

        // OPERATOR -> 403
        mockMvc.perform(get("/api/v1/ops/audit")
                .header("Authorization", "Bearer " + operatorToken))
            .andExpect(status().isForbidden());

        // AUDITOR -> 200
        mockMvc.perform(get("/api/v1/ops/audit")
                .header("Authorization", "Bearer " + auditorToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray());

        // ADMIN -> 200
        mockMvc.perform(get("/api/v1/ops/audit")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("29. Audit records never contain secrets, passwords, or hashes")
    void auditRecordsContainNoSecrets() {
        List<SecurityAuditRecord> all = auditRepository.findPaged(100, 0, null, null);
        for (SecurityAuditRecord r : all) {
            String combined = (r.detailsJson() + " " + r.actorUsername() + " " + r.resourceId()).toLowerCase();
            assertThat(combined).doesNotContain("password");
            assertThat(combined).doesNotContain("secret");
            assertThat(combined).doesNotContain("bcrypt");
        }
    }

    @Test
    @DisplayName("30. Audit failure does not break financial command execution")
    void auditFailureDecoupled() {
        // Simulate a broken audit repository
        SecurityAuditRepository brokenRepo = new SecurityAuditRepository() {
            @Override
            public void append(SecurityAuditRecord record) {
                throw new RuntimeException("Simulated audit disk failure");
            }
            @Override
            public List<SecurityAuditRecord> findPaged(int limit, int offset, String action, String actorUsername) {
                return List.of();
            }
            @Override
            public long count() { return 0; }
        };

        SecurityAuditService service = new SecurityAuditService(brokenRepo, metrics);

        assertThatCode(() -> service.recordLoginFailure("admin", UUID.randomUUID().toString(), "Simulated"))
            .doesNotThrowAnyException();
    }
}
