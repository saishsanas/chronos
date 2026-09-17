package com.chronos.infrastructure.observability;

import com.chronos.api.filter.MdcCorrelationFilter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class OperationalObservabilityTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        com.chronos.TestDatabaseHelper.configureProperties(registry);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private ChronosMetrics chronosMetrics;

    @Test
    @DisplayName("1. Actuator health endpoint returns HTTP 200 OK")
    void testActuatorHealthEndpoint() throws Exception {
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("2. Actuator metrics endpoint is exposed and returns HTTP 200 OK")
    void testActuatorMetricsEndpoint() throws Exception {
        mockMvc.perform(get("/actuator/metrics"))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("3. MDC Correlation Filter propagates custom X-Correlation-Id header into response")
    void testMdcCorrelationFilterWithHeader() throws Exception {
        String customCorrId = UUID.randomUUID().toString();

        mockMvc.perform(get("/actuator/health").header(MdcCorrelationFilter.CORRELATION_ID_HEADER, customCorrId))
            .andExpect(status().isOk())
            .andExpect(header().string(MdcCorrelationFilter.CORRELATION_ID_HEADER, customCorrId));
    }

    @Test
    @DisplayName("4. MDC Correlation Filter generates X-Correlation-Id when absent in request")
    void testMdcCorrelationFilterGeneratesId() throws Exception {
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk())
            .andExpect(header().exists(MdcCorrelationFilter.CORRELATION_ID_HEADER));
    }

    @Test
    @DisplayName("5. ChronosMetrics records custom low-cardinality counters correctly")
    void testChronosMetricsCounters() {
        chronosMetrics.recordCommandProcessed("CreateAccount");
        chronosMetrics.recordInboxDuplicate();
        chronosMetrics.recordOutboxPublished();

        double processedCount = meterRegistry.counter("chronos.commands.processed", "command", "CreateAccount", "outcome", "SUCCESS").count();
        double duplicateCount = meterRegistry.counter("chronos.inbox.duplicates").count();
        double outboxCount = meterRegistry.counter("chronos.outbox.published").count();

        assertThat(processedCount).isGreaterThanOrEqualTo(1.0);
        assertThat(duplicateCount).isGreaterThanOrEqualTo(1.0);
        assertThat(outboxCount).isGreaterThanOrEqualTo(1.0);
    }
}
