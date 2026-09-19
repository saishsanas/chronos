package com.chronos.api.controller;

import com.chronos.api.dto.ApiErrorResponse;
import com.chronos.api.dto.LoginRequest;
import com.chronos.api.dto.LoginResponse;
import com.chronos.application.port.UserRepository;
import com.chronos.application.service.SecurityAuditService;
import com.chronos.domain.security.ApplicationUser;
import com.chronos.domain.security.UserRole;
import com.chronos.infrastructure.observability.ChronosMetrics;
import com.chronos.infrastructure.security.JwtTokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Public authentication endpoint for obtaining JWT bearer tokens")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    // Dummy BCrypt hash to mitigate username enumeration via timing attacks
    private static final String DUMMY_HASH = "$2a$12$e8ZbzK1W5j2mB1d4D8qYgO1I2K3m4N5o6P7q8R9s0T1u2V3w4X5y6";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final SecurityAuditService auditService;
    private final ChronosMetrics metrics;

    public AuthController(
        UserRepository userRepository,
        PasswordEncoder passwordEncoder,
        JwtTokenService jwtTokenService,
        SecurityAuditService auditService,
        @Autowired(required = false) ChronosMetrics metrics
    ) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository must not be null");
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder must not be null");
        this.jwtTokenService = Objects.requireNonNull(jwtTokenService, "jwtTokenService must not be null");
        this.auditService = Objects.requireNonNull(auditService, "auditService must not be null");
        this.metrics = metrics;
    }

    @PostMapping("/login")
    @Operation(summary = "Authenticate user", description = "Verifies user credentials and returns a short-lived signed JWT access token")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        String correlationId = extractCorrelationId(httpRequest);
        String username = request.username().trim();

        Optional<ApplicationUser> userOpt = userRepository.findByUsername(username);

        if (userOpt.isEmpty()) {
            passwordEncoder.matches(request.password(), DUMMY_HASH);
            recordFailure(username, correlationId, "USER_NOT_FOUND");
            return buildUnauthorizedResponse(httpRequest, correlationId);
        }

        ApplicationUser user = userOpt.get();

        if (!user.enabled()) {
            recordFailure(username, correlationId, "USER_DISABLED");
            return buildUnauthorizedResponse(httpRequest, correlationId);
        }

        if (!passwordEncoder.matches(request.password(), user.passwordHash())) {
            recordFailure(username, correlationId, "BAD_CREDENTIALS");
            return buildUnauthorizedResponse(httpRequest, correlationId);
        }

        // Authentication Successful
        String token = jwtTokenService.generateToken(user);
        List<String> roles = user.roles().stream().map(UserRole::name).toList();

        if (metrics != null) {
            metrics.recordSecurityLoginSuccess();
        }
        auditService.recordLoginSuccess(user.userId(), user.username(), correlationId);

        LoginResponse response = LoginResponse.bearer(
            token,
            jwtTokenService.getExpirationSeconds(),
            user.userId(),
            user.username(),
            roles
        );
        return ResponseEntity.ok(response);
    }

    private void recordFailure(String username, String correlationId, String reason) {
        if (metrics != null) {
            metrics.recordSecurityLoginFailure();
        }
        auditService.recordLoginFailure(username, correlationId, reason);
        log.warn("Authentication failed for username '{}': {}", username, reason);
    }

    private ResponseEntity<ApiErrorResponse> buildUnauthorizedResponse(HttpServletRequest httpRequest, String correlationId) {
        ApiErrorResponse error = ApiErrorResponse.of(
            HttpStatus.UNAUTHORIZED.value(),
            "UNAUTHORIZED",
            "Invalid username or password",
            httpRequest.getRequestURI(),
            correlationId
        );
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
    }

    private String extractCorrelationId(HttpServletRequest request) {
        String header = request.getHeader("X-Correlation-Id");
        return (header != null && !header.isBlank()) ? header.trim() : "N/A";
    }
}
