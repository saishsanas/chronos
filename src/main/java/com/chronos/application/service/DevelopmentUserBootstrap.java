package com.chronos.application.service;

import com.chronos.application.port.UserRepository;
import com.chronos.domain.security.ApplicationUser;
import com.chronos.domain.security.UserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Component
public class DevelopmentUserBootstrap implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DevelopmentUserBootstrap.class);

    // Fixed deterministic UUIDs for development/test reproducibility
    public static final UUID ADMIN_USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    public static final UUID OPERATOR_USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    public static final UUID AUDITOR_USER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    public static final String DEV_ADMIN_PASSWORD = "AdminSecret123!";
    public static final String DEV_OPERATOR_PASSWORD = "OperatorSecret123!";
    public static final String DEV_AUDITOR_PASSWORD = "AuditorSecret123!";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final boolean bootstrapEnabled;

    public DevelopmentUserBootstrap(
        UserRepository userRepository,
        PasswordEncoder passwordEncoder,
        @Value("${chronos.security.bootstrap-dev-users:false}") boolean bootstrapEnabled
    ) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository must not be null");
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder must not be null");
        this.bootstrapEnabled = bootstrapEnabled;
    }

    @Override
    public void run(String... args) {
        if (!bootstrapEnabled) {
            log.info("Development user bootstrap is disabled by default. No users seeded.");
            return;
        }

        if (userRepository.count() > 0) {
            log.info("User repository already contains users. Skipping development user bootstrap.");
            return;
        }

        log.warn("Bootstrapping development users for local/test execution. NEVER enable in production!");

        ApplicationUser admin = ApplicationUser.createWithId(
            ADMIN_USER_ID,
            "admin",
            passwordEncoder.encode(DEV_ADMIN_PASSWORD),
            Set.of(UserRole.ADMIN)
        );

        ApplicationUser operator = ApplicationUser.createWithId(
            OPERATOR_USER_ID,
            "operator",
            passwordEncoder.encode(DEV_OPERATOR_PASSWORD),
            Set.of(UserRole.OPERATOR)
        );

        ApplicationUser auditor = ApplicationUser.createWithId(
            AUDITOR_USER_ID,
            "auditor",
            passwordEncoder.encode(DEV_AUDITOR_PASSWORD),
            Set.of(UserRole.AUDITOR)
        );

        userRepository.save(admin);
        userRepository.save(operator);
        userRepository.save(auditor);

        log.info("Successfully seeded development users: [admin, operator, auditor]");
    }
}
