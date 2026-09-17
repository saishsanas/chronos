package com.chronos;

import org.springframework.test.context.DynamicPropertyRegistry;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

public class TestDatabaseHelper {

    public static void configureProperties(DynamicPropertyRegistry registry) {
        String containerUrl = "jdbc:postgresql://localhost:5432/chronos_db";
        String testDbUrl = "jdbc:postgresql://localhost:5432/chronos_test_db";

        // Check if Postgres container is running on 5432 with chronos_user
        try (Connection conn = DriverManager.getConnection(containerUrl, "chronos_user", "chronos_password")) {
            // Container Postgres is running! Create chronos_test_db if it doesn't exist
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("CREATE DATABASE chronos_test_db");
            } catch (Exception ignored) {
                // Database chronos_test_db already exists
            }
            registry.add("spring.datasource.url", () -> testDbUrl);
            registry.add("spring.datasource.username", () -> "chronos_user");
            registry.add("spring.datasource.password", () -> "chronos_password");
            registry.add("spring.flyway.locations", () -> "classpath:db/migration");
            return;
        } catch (Exception ignored) {
        }

        // Fallback for standalone local Postgres test_user setup
        registry.add("spring.datasource.url", () -> testDbUrl);
        registry.add("spring.datasource.username", () -> "test_user");
        registry.add("spring.datasource.password", () -> "test_password");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
    }
}
