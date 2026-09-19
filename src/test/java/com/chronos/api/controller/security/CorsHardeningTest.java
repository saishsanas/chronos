package com.chronos.api.controller.security;

import com.chronos.TestDatabaseHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class CorsHardeningTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        TestDatabaseHelper.configureProperties(registry);
        registry.add("chronos.cors.allowed-origins", () -> "http://localhost:5173,http://trusted-internal-domain.com");
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("31. Configured allowed frontend origin receives Access-Control-Allow-Origin header")
    void allowedOriginAccepted() throws Exception {
        mockMvc.perform(options("/api/v1/auth/login")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST"))
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }

    @Test
    @DisplayName("32. Arbitrary disallowed origin does NOT receive Access-Control-Allow-Origin header")
    void disallowedOriginRejected() throws Exception {
        mockMvc.perform(options("/api/v1/auth/login")
                .header("Origin", "http://malicious-attacker-site.com")
                .header("Access-Control-Request-Method", "POST"))
            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
