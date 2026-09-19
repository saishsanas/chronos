package com.chronos.infrastructure.security;

import com.chronos.api.dto.ApiErrorResponse;
import com.chronos.application.service.SecurityAuditService;
import com.chronos.infrastructure.observability.ChronosMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    private final String jwtSecret;
    private final String allowedOrigins;
    private final ObjectMapper objectMapper;
    private final ChronosMetrics metrics;

    public SecurityConfig(
        @Value("${chronos.security.jwt.secret:}") String jwtSecret,
        @Value("${chronos.cors.allowed-origins:http://localhost:5173}") String allowedOrigins,
        ObjectMapper objectMapper,
        @Autowired(required = false) ChronosMetrics metrics
    ) {
        this.jwtSecret = jwtSecret;
        this.allowedOrigins = allowedOrigins;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.metrics = metrics;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public SecretKey jwtSecretKey() {
        if (jwtSecret == null || jwtSecret.trim().isEmpty()) {
            throw new IllegalStateException(
                "CHRONOS_JWT_SECRET environment variable or chronos.security.jwt.secret property is not configured. " +
                "A 256-bit (at least 32 characters) secret key is required for JWT signing."
            );
        }
        byte[] secretBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException(
                "JWT signing secret is too short: must be at least 32 bytes (256 bits) for HMAC-SHA256."
            );
        }
        return new SecretKeySpec(secretBytes, "HmacSHA256");
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKey secretKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey));
    }

    @Bean
    public JwtDecoder jwtDecoder(SecretKey secretKey) {
        return NimbusJwtDecoder.withSecretKey(secretKey).build();
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        Converter<Jwt, Collection<GrantedAuthority>> grantedAuthoritiesConverter = jwt -> {
            List<String> roles = jwt.getClaimAsStringList("roles");
            if (roles == null || roles.isEmpty()) {
                return Collections.emptyList();
            }
            return roles.stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()))
                .toList();
        };

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(grantedAuthoritiesConverter);
        return converter;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("X-Correlation-Id", "Idempotency-Key", "Authorization"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, authException) -> {
            String corrId = extractCorrelationId(request);
            log.warn("Unauthorized access attempt to [{}]: {}", request.getRequestURI(), authException.getMessage());
            if (metrics != null) {
                metrics.recordSecurityAccessDenied();
            }
            writeErrorResponse(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required or token invalid/expired", request.getRequestURI(), corrId);
        };
    }

    @Bean
    public AccessDeniedHandler accessDeniedHandler(@Autowired(required = false) SecurityAuditService auditService) {
        return (request, response, accessDeniedException) -> {
            String corrId = extractCorrelationId(request);
            log.warn("Access denied to [{}]: {}", request.getRequestURI(), accessDeniedException.getMessage());
            if (metrics != null) {
                metrics.recordSecurityAccessDenied();
            }
            if (auditService != null) {
                auditService.recordSecurityDenied(null, "ENDPOINT", request.getRequestURI(), corrId, accessDeniedException.getMessage());
            }
            writeErrorResponse(response, HttpStatus.FORBIDDEN, "FORBIDDEN", "Insufficient role or permissions for this resource", request.getRequestURI(), corrId);
        };
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
        HttpSecurity http,
        AuthenticationEntryPoint authEntryPoint,
        AccessDeniedHandler accessDeniedHandler,
        JwtAuthenticationConverter jwtAuthConverter,
        CorsConfigurationSource corsConfigurationSource
    ) throws Exception {
        http
            // Stateless REST API using bearer tokens; CSRF disabled
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(authEntryPoint)
                .accessDeniedHandler(accessDeniedHandler)
            )
            .authorizeHttpRequests(auth -> auth
                // 1. Public Authentication & Documentation Endpoints
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()

                // 2. Sensitive Actuator Endpoints (require ADMIN)
                .requestMatchers("/actuator/**").hasRole("ADMIN")

                // 3. Administrative Projection Operations (require ADMIN)
                .requestMatchers("/api/v1/ops/projections/**").hasRole("ADMIN")

                // 4. Security Audit Operations (require ADMIN or AUDITOR)
                .requestMatchers("/api/v1/ops/audit/**").hasAnyRole("ADMIN", "AUDITOR")

                // 5. Account Domain Commands (require OPERATOR or ADMIN)
                .requestMatchers(HttpMethod.POST, "/api/v1/accounts", "/api/v1/accounts/**").hasAnyRole("OPERATOR", "ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/v1/accounts/**").hasAnyRole("OPERATOR", "ADMIN")

                // 6. Account Domain Queries (require AUDITOR, OPERATOR, or ADMIN)
                .requestMatchers(HttpMethod.GET, "/api/v1/accounts/**").hasAnyRole("AUDITOR", "OPERATOR", "ADMIN")

                // 7. Default: Any other request must be authenticated
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthConverter))
                .authenticationEntryPoint(authEntryPoint)
                .accessDeniedHandler(accessDeniedHandler)
            );

        return http.build();
    }

    private void writeErrorResponse(
        HttpServletResponse response,
        HttpStatus status,
        String error,
        String message,
        String path,
        String correlationId
    ) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        ApiErrorResponse body = ApiErrorResponse.of(
            status.value(),
            error,
            message,
            path,
            correlationId
        );
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    private String extractCorrelationId(HttpServletRequest request) {
        String header = request.getHeader("X-Correlation-Id");
        return (header != null && !header.isBlank()) ? header.trim() : "N/A";
    }
}
