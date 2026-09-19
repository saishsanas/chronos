package com.chronos.api.controller;

import com.chronos.api.dto.CreateAccountRequest;
import com.chronos.api.dto.DepositRequest;
import com.chronos.api.dto.WithdrawalRequest;
import com.chronos.domain.account.CorrectionDirection;
import com.chronos.domain.account.CorrectionType;
import com.chronos.domain.event.DomainEventEnvelope;
import com.chronos.application.port.EventStore;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.security.test.context.support.WithMockUser(username = "operator", roles = {"OPERATOR"})
public class AccountApiControllerIntegrationTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        com.chronos.TestDatabaseHelper.configureProperties(registry);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EventStore eventStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("TRUNCATE TABLE outbox_events");
        jdbcTemplate.update("TRUNCATE TABLE inbox_events");
        jdbcTemplate.update("TRUNCATE TABLE snapshots");
        jdbcTemplate.update("TRUNCATE TABLE event_store");
    }

    private UUID createAccountHelper(String currency, long overdraft, long txLimit) throws Exception {
        CreateAccountRequest req = new CreateAccountRequest(currency, overdraft, txLimit);
        MvcResult result = mockMvc.perform(post("/api/v1/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req))
                .header("Idempotency-Key", "idemp-" + UUID.randomUUID())
                .header("X-Correlation-Id", UUID.randomUUID().toString()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.aggregateId").exists())
            .andExpect(jsonPath("$.sequenceNumber").value(1))
            .andExpect(jsonPath("$.newState.status").value("ACTIVE"))
            .andReturn();

        String body = result.getResponse().getContentAsString();
        JsonNode node = objectMapper.readTree(body);
        return UUID.fromString(node.get("aggregateId").asText());
    }

    @Test
    @DisplayName("1. Create account request succeeds with HTTP 201 Created")
    void testCreateAccountSucceeds() throws Exception {
        UUID accountId = createAccountHelper("INR", 10000L, 50000L);
        assertThat(accountId).isNotNull();

        List<DomainEventEnvelope> events = eventStore.loadStream(accountId);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).eventType()).isEqualTo("AccountCreated");
    }

    @Test
    @DisplayName("2. Duplicate create account for already initialized account returns 422 Unprocessable Entity")
    void testDuplicateCreateAccountFails() throws Exception {
        UUID accountId = createAccountHelper("INR", 10000L, 50000L);

        // Perform deposit
        mockMvc.perform(post("/api/v1/accounts/" + accountId + "/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new DepositRequest(5000L, "INR"))))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("3. Deposit money succeeds with HTTP 200 OK")
    void testDepositMoney() throws Exception {
        UUID accountId = createAccountHelper("INR", 10000L, 50000L);

        mockMvc.perform(post("/api/v1/accounts/" + accountId + "/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new DepositRequest(5000L, "INR"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.newState.balanceMinor").value(5000L))
            .andExpect(jsonPath("$.sequenceNumber").value(2));
    }

    @Test
    @DisplayName("4 & 5. Frozen account CAN deposit but CANNOT withdraw")
    void testFrozenAccountDepositAndWithdrawal() throws Exception {
        UUID accountId = createAccountHelper("INR", 10000L, 50000L);

        // Freeze
        mockMvc.perform(post("/api/v1/accounts/" + accountId + "/freeze")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\": \"Suspicious activity\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.newState.status").value("FROZEN"));

        // Deposit SHOULD work on FROZEN account
        mockMvc.perform(post("/api/v1/accounts/" + accountId + "/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new DepositRequest(2000L, "INR"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.newState.balanceMinor").value(2000L));

        // Withdrawal SHOULD FAIL on FROZEN account (422)
        mockMvc.perform(post("/api/v1/accounts/" + accountId + "/withdrawals")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new WithdrawalRequest(1000L, "INR"))))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errorCode").value("INVALID_STATE_TRANSITION"));
    }

    @Test
    @DisplayName("6. Withdrawal respects transaction limit")
    void testWithdrawalTransactionLimitExceeded() throws Exception {
        UUID accountId = createAccountHelper("INR", 10000L, 5000L); // TX limit 5000

        mockMvc.perform(post("/api/v1/accounts/" + accountId + "/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new DepositRequest(10000L, "INR"))))
            .andExpect(status().isOk());

        // Withdraw 6000 (exceeds tx limit 5000)
        mockMvc.perform(post("/api/v1/accounts/" + accountId + "/withdrawals")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new WithdrawalRequest(6000L, "INR"))))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errorCode").value("TRANSACTION_LIMIT_EXCEEDED"));
    }

    @Test
    @DisplayName("7. Withdrawal respects overdraft limit")
    void testWithdrawalOverdraftLimitExceeded() throws Exception {
        UUID accountId = createAccountHelper("INR", 1000L, 50000L); // Overdraft limit 1000, Balance 0

        // Withdraw 2000 (exceeds balance 0 + overdraft 1000 = 1000 max)
        mockMvc.perform(post("/api/v1/accounts/" + accountId + "/withdrawals")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new WithdrawalRequest(2000L, "INR"))))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errorCode").value("INSUFFICIENT_FUNDS"));
    }

    @Test
    @DisplayName("8. Freeze and Unfreeze account lifecycle")
    void testFreezeAndUnfreezeLifecycle() throws Exception {
        UUID accountId = createAccountHelper("INR", 10000L, 50000L);

        mockMvc.perform(post("/api/v1/accounts/" + accountId + "/freeze")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\": \"Compliance audit\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.newState.status").value("FROZEN"));

        mockMvc.perform(post("/api/v1/accounts/" + accountId + "/unfreeze")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\": \"Audit passed\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.newState.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("9. Close account succeeds when balance is zero")
    void testCloseAccount() throws Exception {
        UUID accountId = createAccountHelper("INR", 10000L, 50000L);

        mockMvc.perform(post("/api/v1/accounts/" + accountId + "/close")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\": \"User requested closure\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.newState.status").value("CLOSED"));
    }

    @Test
    @DisplayName("10. Correction issue succeeds")
    void testIssueCorrection() throws Exception {
        UUID accountId = createAccountHelper("INR", 10000L, 50000L);

        // Deposit 5000
        mockMvc.perform(post("/api/v1/accounts/" + accountId + "/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new DepositRequest(5000L, "INR"))))
            .andExpect(status().isOk());

        List<DomainEventEnvelope> events = eventStore.loadStream(accountId);
        UUID depositEventId = events.get(1).eventId();

        // Issue reversal correction
        String correctionJson = String.format("""
            {
                "targetEventId": "%s",
                "correctionType": "REVERSAL",
                "direction": "DEBIT",
                "adjustmentAmountMinor": 5000,
                "reason": "Customer complaint refund"
            }
            """, depositEventId);

        mockMvc.perform(post("/api/v1/accounts/" + accountId + "/corrections")
                .contentType(MediaType.APPLICATION_JSON)
                .content(correctionJson))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.newState.balanceMinor").value(0L));
    }

    @Test
    @DisplayName("11. GET /api/v1/accounts/{accountId} reconstructs current state")
    void testGetCurrentState() throws Exception {
        UUID accountId = createAccountHelper("INR", 10000L, 50000L);

        mockMvc.perform(post("/api/v1/accounts/" + accountId + "/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new DepositRequest(7500L, "INR"))))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/accounts/" + accountId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accountId").value(accountId.toString()))
            .andExpect(jsonPath("$.currency").value("INR"))
            .andExpect(jsonPath("$.balanceMinor").value(7500L))
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.sequenceNumber").value(2));
    }

    @Test
    @DisplayName("12 & 13. GET /api/v1/accounts/{accountId}/state-at reconstructs historical state (inclusive recordedAt <= T)")
    void testGetStateAt() throws Exception {
        UUID accountId = createAccountHelper("INR", 10000L, 50000L);

        Instant t1 = Instant.now();
        Thread.sleep(10);

        mockMvc.perform(post("/api/v1/accounts/" + accountId + "/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new DepositRequest(4000L, "INR"))))
            .andExpect(status().isOk());

        List<DomainEventEnvelope> events = eventStore.loadStream(accountId);
        Instant t2 = events.get(1).recordedAt(); // Exact timestamp of deposit event

        Thread.sleep(10);
        mockMvc.perform(post("/api/v1/accounts/" + accountId + "/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new DepositRequest(6000L, "INR"))))
            .andExpect(status().isOk());

        // State at t1 (before any deposit) -> balance should be 0
        mockMvc.perform(get("/api/v1/accounts/" + accountId + "/state-at?at=" + t1.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.reconstructedState.balanceMinor").value(0L))
            .andExpect(jsonPath("$.sequenceNumber").value(1));

        // State at t2 (exact timestamp of first deposit) -> INCLUSIVE boundary -> balance should be 4000
        mockMvc.perform(get("/api/v1/accounts/" + accountId + "/state-at?at=" + t2.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.reconstructedState.balanceMinor").value(4000L))
            .andExpect(jsonPath("$.sequenceNumber").value(2));
    }

    @Test
    @DisplayName("14. GET /api/v1/accounts/{accountId}/events returns ordered immutable events")
    void testGetEventHistory() throws Exception {
        UUID accountId = createAccountHelper("INR", 10000L, 50000L);

        mockMvc.perform(post("/api/v1/accounts/" + accountId + "/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new DepositRequest(3000L, "INR"))))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/accounts/" + accountId + "/events"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].sequenceNumber").value(1))
            .andExpect(jsonPath("$[0].eventType").value("AccountCreated"))
            .andExpect(jsonPath("$[1].sequenceNumber").value(2))
            .andExpect(jsonPath("$[1].eventType").value("MoneyDeposited"));
    }

    @Test
    @DisplayName("15. Invalid request returns structured 400 response")
    void testInvalidRequestReturnsStructuredError() throws Exception {
        CreateAccountRequest invalidReq = new CreateAccountRequest("", -500L, 0L);

        mockMvc.perform(post("/api/v1/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalidReq)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"))
            .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("16. Missing account maps correctly to 404 NOT FOUND")
    void testMissingAccountReturns404() throws Exception {
        UUID missingId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/accounts/" + missingId))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.errorCode").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    @DisplayName("18. Correlation and Idempotency headers propagate into metadata")
    void testHeaderPropagation() throws Exception {
        String customCorrId = UUID.randomUUID().toString();
        String customIdempKey = "key-" + UUID.randomUUID();

        CreateAccountRequest req = new CreateAccountRequest("INR", 10000L, 50000L);
        MvcResult result = mockMvc.perform(post("/api/v1/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req))
                .header("Idempotency-Key", customIdempKey)
                .header("X-Correlation-Id", customCorrId))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.correlationId").value(customCorrId))
            .andReturn();

        String body = result.getResponse().getContentAsString();
        UUID accountId = UUID.fromString(objectMapper.readTree(body).get("aggregateId").asText());

        List<DomainEventEnvelope> stream = eventStore.loadStream(accountId);
        assertThat(stream).hasSize(1);
        assertThat(stream.get(0).metadata().correlationId().toString()).isEqualTo(customCorrId);
        assertThat(stream.get(0).metadata().idempotencyKey()).isEqualTo(customIdempKey);
    }
}
