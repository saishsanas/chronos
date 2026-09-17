package com.chronos;

import org.springframework.test.context.DynamicPropertyRegistry;

import java.sql.Connection;
import java.sql.DriverManager;

public class TestDatabaseHelper {

    public static void configureProperties(DynamicPropertyRegistry registry) {
        String composeUrl = "jdbc:postgresql://localhost:5432/chronos_db";
        String testUrl = "jdbc:postgresql://localhost:5432/chronos_test_db";

        boolean isComposeRunning = false;
        try (Connection conn = DriverManager.getConnection(composeUrl, "chronos_user", "chronos_password")) {
            isComposeRunning = true;
        } catch (Exception ignored) {
        }

        if (isComposeRunning) {
            registry.add("spring.datasource.url", () -> composeUrl);
            registry.add("spring.datasource.username", () -> "chronos_user");
            registry.add("spring.datasource.password", () -> "chronos_password");
        } else {
            registry.add("spring.datasource.url", () -> testUrl);
            registry.add("spring.datasource.username", () -> "test_user");
            registry.add("spring.datasource.password", () -> "test_password");
        }
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
    }
}
