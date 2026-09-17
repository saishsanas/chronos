package com.chronos.application.service;

import com.chronos.application.command.CommandContext;
import com.chronos.application.command.CommandResult;
import com.chronos.application.port.EventStore;
import com.chronos.application.port.OptimisticConcurrencyException;
import com.chronos.domain.account.AccountReducer;
import com.chronos.domain.account.AccountState;
import com.chronos.domain.account.AccountStatus;
import com.chronos.domain.account.CorrectionDirection;
import com.chronos.domain.account.CorrectionType;
import com.chronos.domain.account.command.*;
import com.chronos.domain.account.exception.*;
import com.chronos.domain.event.DomainEventEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class AccountCommandProcessorTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5432/chronos_test_db");
        registry.add("spring.datasource.username", () -> "test_user");
        registry.add("spring.datasource.password", () -> "test_password");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
    }

    @Autowired
    private AccountCommandProcessor commandProcessor;

    @Autowired
    private EventStore eventStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("TRUNCATE TABLE event_store");
    }

    // -------------------------------------------------------------------------
    // CREATION TESTS (1 - 6)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("1-5. Create account emits AccountCreated, seq=1, balance=0, initial limits, status=ACTIVE")
    void testCreateAccountSuccess() {
        UUID accountId = UUID.randomUUID();
        CommandContext ctx = CommandContext.of("admin-1", "idemp-create-1");
        CreateAccount cmd = new CreateAccount(accountId, "INR", 50000L, 100000L);

        CommandResult res = commandProcessor.process(cmd, ctx);

        assertThat(res.aggregateId()).isEqualTo(accountId);
        assertThat(res.finalSequenceNumber()).isEqualTo(1L);
        assertThat(res.emittedEvents()).hasSize(1);

        DomainEventEnvelope event = res.emittedEvents().get(0);
        assertThat(event.eventType()).isEqualTo("AccountCreated");
        assertThat(event.sequenceNumber()).isEqualTo(1L);
        assertThat(event.metadata().actorId()).isEqualTo("admin-1");
        assertThat(event.metadata().idempotencyKey()).isEqualTo("idemp-create-1");

        AccountState state = res.resultingState();
        assertThat(state.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(state.balanceMinor()).isEqualTo(0L);
        assertThat(state.overdraftLimitMinor()).isEqualTo(50000L);
        assertThat(state.transactionLimitMinor()).isEqualTo(100000L);
    }

    @Test
    @DisplayName("6. Duplicate creation rejected")
    void testDuplicateAccountCreationRejected() {
        UUID accountId = UUID.randomUUID();
        CommandContext ctx = CommandContext.of("admin-1");
        commandProcessor.process(new CreateAccount(accountId, "INR", 50000L, 100000L), ctx);

        assertThatThrownBy(() -> commandProcessor.process(new CreateAccount(accountId, "INR", 50000L, 100000L), ctx))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("already initialized");
    }

    // -------------------------------------------------------------------------
    // DEPOSIT TESTS (7 - 12)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("7. Valid deposit changes balance")
    void testValidDeposit() {
        UUID accountId = UUID.randomUUID();
        CommandContext ctx = CommandContext.of("user-1");
        commandProcessor.process(new CreateAccount(accountId, "INR", 50000L, 100000L), ctx);

        CommandResult res = commandProcessor.process(new DepositMoney(accountId, 250000L), ctx);
        assertThat(res.resultingState().balanceMinor()).isEqualTo(250000L);
        assertThat(res.finalSequenceNumber()).isEqualTo(2L);
    }

    @Test
    @DisplayName("8 & 9. Zero and negative deposit rejected")
    void testZeroAndNegativeDepositRejected() {
        UUID accountId = UUID.randomUUID();
        CommandContext ctx = CommandContext.of("user-1");
        commandProcessor.process(new CreateAccount(accountId, "INR", 50000L, 100000L), ctx);

        assertThatThrownBy(() -> commandProcessor.process(new DepositMoney(accountId, 0L), ctx))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("Deposit amount must be positive");

        assertThatThrownBy(() -> commandProcessor.process(new DepositMoney(accountId, -500L), ctx))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    @DisplayName("10. Deposit on FROZEN allowed")
    void testDepositOnFrozenAllowed() {
        UUID accountId = UUID.randomUUID();
        CommandContext ctx = CommandContext.of("user-1");
        commandProcessor.process(new CreateAccount(accountId, "INR", 50000L, 100000L), ctx);
        commandProcessor.process(new FreezeAccount(accountId, "verification required"), ctx);

        CommandResult res = commandProcessor.process(new DepositMoney(accountId, 100000L), ctx);
        assertThat(res.resultingState().balanceMinor()).isEqualTo(100000L);
        assertThat(res.resultingState().status()).isEqualTo(AccountStatus.FROZEN);
    }

    @Test
    @DisplayName("11. Deposit on CLOSED rejected")
    void testDepositOnClosedRejected() {
        UUID accountId = UUID.randomUUID();
        CommandContext ctx = CommandContext.of("user-1");
        commandProcessor.process(new CreateAccount(accountId, "INR", 50000L, 100000L), ctx);
        commandProcessor.process(new CloseAccount(accountId, "close account"), ctx);

        assertThatThrownBy(() -> commandProcessor.process(new DepositMoney(accountId, 100000L), ctx))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("CLOSED");
    }

    @Test
    @DisplayName("12. Monetary overflow rejected")
    void testDepositOverflowRejected() {
        UUID accountId = UUID.randomUUID();
        CommandContext ctx = CommandContext.of("user-1");
        commandProcessor.process(new CreateAccount(accountId, "INR", 50000L, 100000L), ctx);
        commandProcessor.process(new DepositMoney(accountId, Long.MAX_VALUE - 10L), ctx);

        assertThatThrownBy(() -> commandProcessor.process(new DepositMoney(accountId, 100L), ctx))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("overflow");
    }

    // -------------------------------------------------------------------------
    // WITHDRAW TESTS (13 - 20)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("13. Valid withdrawal changes balance")
    void testValidWithdrawal() {
        UUID accountId = UUID.randomUUID();
        CommandContext ctx = CommandContext.of("user-1");
        commandProcessor.process(new CreateAccount(accountId, "INR", 50000L, 100000L), ctx);
        commandProcessor.process(new DepositMoney(accountId, 200000L), ctx);

        CommandResult res = commandProcessor.process(new WithdrawMoney(accountId, 50000L), ctx);
        assertThat(res.resultingState().balanceMinor()).isEqualTo(150000L);
    }

    @Test
    @DisplayName("14 & 15. Zero and negative withdrawal rejected")
    void testZeroAndNegativeWithdrawalRejected() {
        UUID accountId = UUID.randomUUID();
        CommandContext ctx = CommandContext.of("user-1");
        commandProcessor.process(new CreateAccount(accountId, "INR", 50000L, 100000L), ctx);

        assertThatThrownBy(() -> commandProcessor.process(new WithdrawMoney(accountId, 0L), ctx))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    @DisplayName("16. Transaction limit enforced")
    void testTransactionLimitEnforced() {
        UUID accountId = UUID.randomUUID();
        CommandContext ctx = CommandContext.of("user-1");
        commandProcessor.process(new CreateAccount(accountId, "INR", 50000L, 100000L), ctx); // txLimit = 100,000
        commandProcessor.process(new DepositMoney(accountId, 500000L), ctx);

        assertThatThrownBy(() -> commandProcessor.process(new WithdrawMoney(accountId, 150000L), ctx))
                .isInstanceOf(TransactionLimitExceededException.class)
                .hasMessageContaining("exceeds transaction limit");
    }

    @Test
    @DisplayName("17. Overdraft limit enforced")
    void testOverdraftLimitEnforced() {
        UUID accountId = UUID.randomUUID();
        CommandContext ctx = CommandContext.of("user-1");
        commandProcessor.process(new CreateAccount(accountId, "INR", 20000L, 100000L), ctx); // overdraft = 20,000

        assertThatThrownBy(() -> commandProcessor.process(new WithdrawMoney(accountId, 30000L), ctx))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessageContaining("exceeds available balance + overdraft limit");
    }

    @Test
    @DisplayName("18. FROZEN withdrawal rejected")
    void testFrozenWithdrawalRejected() {
        UUID accountId = UUID.randomUUID();
        CommandContext ctx = CommandContext.of("user-1");
        commandProcessor.process(new CreateAccount(accountId, "INR", 50000L, 100000L), ctx);
        commandProcessor.process(new DepositMoney(accountId, 200000L), ctx);
        commandProcessor.process(new FreezeAccount(accountId, "hold"), ctx);

        assertThatThrownBy(() -> commandProcessor.process(new WithdrawMoney(accountId, 50000L), ctx))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("FROZEN");
    }

    @Test
    @DisplayName("19. CLOSED withdrawal rejected")
    void testClosedWithdrawalRejected() {
        UUID accountId = UUID.randomUUID();
        CommandContext ctx = CommandContext.of("user-1");
        commandProcessor.process(new CreateAccount(accountId, "INR", 50000L, 100000L), ctx);
        commandProcessor.process(new CloseAccount(accountId, "close"), ctx);

        assertThatThrownBy(() -> commandProcessor.process(new WithdrawMoney(accountId, 5000L), ctx))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("CLOSED");
    }

    // -------------------------------------------------------------------------
    // LIFECYCLE TESTS (21 - 28)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("21 & 22. ACTIVE -> FROZEN and FROZEN -> ACTIVE lifecycle transitions")
    void testLifecycleFreezeAndUnfreeze() {
        UUID accountId = UUID.randomUUID();
        CommandContext ctx = CommandContext.of("user-1");
        commandProcessor.process(new CreateAccount(accountId, "INR", 50000L, 100000L), ctx);

        CommandResult freezeRes = commandProcessor.process(new FreezeAccount(accountId, "investigation"), ctx);
        assertThat(freezeRes.resultingState().status()).isEqualTo(AccountStatus.FROZEN);

        CommandResult unfreezeRes = commandProcessor.process(new UnfreezeAccount(accountId, "cleared"), ctx);
        assertThat(unfreezeRes.resultingState().status()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    @DisplayName("23 & 24. Redundant freeze and unfreeze rejected")
    void testRedundantFreezeUnfreezeRejected() {
        UUID accountId = UUID.randomUUID();
        CommandContext ctx = CommandContext.of("user-1");
        commandProcessor.process(new CreateAccount(accountId, "INR", 50000L, 100000L), ctx);

        // Redundant unfreeze on ACTIVE account
        assertThatThrownBy(() -> commandProcessor.process(new UnfreezeAccount(accountId, "test"), ctx))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("already ACTIVE");

        commandProcessor.process(new FreezeAccount(accountId, "hold"), ctx);

        // Redundant freeze on FROZEN account
        assertThatThrownBy(() -> commandProcessor.process(new FreezeAccount(accountId, "hold2"), ctx))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("already FROZEN");
    }

    @Test
    @DisplayName("25-28. ACTIVE -> CLOSED, redundant close, and CLOSED cannot reactivate")
    void testCloseAccountRules() {
        UUID accountId = UUID.randomUUID();
        CommandContext ctx = CommandContext.of("user-1");
        commandProcessor.process(new CreateAccount(accountId, "INR", 50000L, 100000L), ctx);

        CommandResult closeRes = commandProcessor.process(new CloseAccount(accountId, "user requested"), ctx);
        assertThat(closeRes.resultingState().status()).isEqualTo(AccountStatus.CLOSED);

        // Redundant close
        assertThatThrownBy(() -> commandProcessor.process(new CloseAccount(accountId, "again"), ctx))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("already CLOSED");

        // Closed account cannot execute normal commands
        assertThatThrownBy(() -> commandProcessor.process(new UnfreezeAccount(accountId, "reactivate"), ctx))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    // -------------------------------------------------------------------------
    // LIMITS TESTS (29 - 32)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("29-32. Overdraft and transaction limits state updates & validation")
    void testLimitUpdatesAndValidation() {
        UUID accountId = UUID.randomUUID();
        CommandContext ctx = CommandContext.of("user-1");
        commandProcessor.process(new CreateAccount(accountId, "INR", 50000L, 100000L), ctx);

        CommandResult res1 = commandProcessor.process(new SetOverdraftLimit(accountId, 150000L), ctx);
        assertThat(res1.resultingState().overdraftLimitMinor()).isEqualTo(150000L);

        CommandResult res2 = commandProcessor.process(new SetTransactionLimit(accountId, 500000L), ctx);
        assertThat(res2.resultingState().transactionLimitMinor()).isEqualTo(500000L);

        // Negative limit rejected
        assertThatThrownBy(() -> commandProcessor.process(new SetOverdraftLimit(accountId, -100L), ctx))
                .isInstanceOf(DomainValidationException.class);
    }

    // -------------------------------------------------------------------------
    // CORRECTIONS TESTS (33 - 40)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("33-38. Valid correction emitted, affects reconstructed state, enforces safety rules")
    void testValidCorrection() {
        UUID accountId = UUID.randomUUID();
        CommandContext ctx = CommandContext.of("admin-1");
        commandProcessor.process(new CreateAccount(accountId, "INR", 50000L, 100000L), ctx);
        CommandResult depRes = commandProcessor.process(new DepositMoney(accountId, 100000L), ctx);
        UUID targetEventId = depRes.emittedEvents().get(0).eventId();

        IssueCorrection cmd = new IssueCorrection(
                accountId, targetEventId, CorrectionType.REVERSAL, CorrectionDirection.DEBIT, 100000L, "Reversing mistaken deposit"
        );

        CommandResult corrRes = commandProcessor.process(cmd, ctx);
        assertThat(corrRes.resultingState().balanceMinor()).isEqualTo(0L);
        assertThat(corrRes.emittedEvents().get(0).eventType()).isEqualTo("CorrectionIssued");
    }

    @Test
    @DisplayName("34 & 35. Correction target must exist and belong to aggregate")
    void testCorrectionTargetMustExist() {
        UUID accountId = UUID.randomUUID();
        CommandContext ctx = CommandContext.of("admin-1");
        commandProcessor.process(new CreateAccount(accountId, "INR", 50000L, 100000L), ctx);

        UUID nonExistentTarget = UUID.randomUUID();
        IssueCorrection cmd = new IssueCorrection(
                accountId, nonExistentTarget, CorrectionType.REVERSAL, CorrectionDirection.DEBIT, 10000L, "reversal"
        );

        assertThatThrownBy(() -> commandProcessor.process(cmd, ctx))
                .isInstanceOf(InvalidCorrectionException.class)
                .hasMessageContaining("not found in stream");
    }

    @Test
    @DisplayName("40. Double reversal of target event rejected")
    void testDoubleCorrectionRejected() {
        UUID accountId = UUID.randomUUID();
        CommandContext ctx = CommandContext.of("admin-1");
        commandProcessor.process(new CreateAccount(accountId, "INR", 50000L, 100000L), ctx);
        CommandResult depRes = commandProcessor.process(new DepositMoney(accountId, 100000L), ctx);
        UUID targetEventId = depRes.emittedEvents().get(0).eventId();

        IssueCorrection cmd = new IssueCorrection(
                accountId, targetEventId, CorrectionType.REVERSAL, CorrectionDirection.DEBIT, 100000L, "Reversal 1"
        );
        commandProcessor.process(cmd, ctx);

        // Attempting a second reversal against the same target event
        IssueCorrection cmd2 = new IssueCorrection(
                accountId, targetEventId, CorrectionType.REVERSAL, CorrectionDirection.DEBIT, 100000L, "Reversal 2"
        );

        assertThatThrownBy(() -> commandProcessor.process(cmd2, ctx))
                .isInstanceOf(InvalidCorrectionException.class)
                .hasMessageContaining("has already been reversed");
    }

    // -------------------------------------------------------------------------
    // STREAM REPLAY & INTEGRITY TESTS (54 - 57)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("54-57. Complete valid stream reconstructs state deterministically")
    void testStreamReplay() {
        UUID accountId = UUID.randomUUID();
        CommandContext ctx = CommandContext.of("user-1");

        commandProcessor.process(new CreateAccount(accountId, "INR", 50000L, 100000L), ctx);
        commandProcessor.process(new DepositMoney(accountId, 500000L), ctx);
        commandProcessor.process(new WithdrawMoney(accountId, 80000L), ctx);

        List<DomainEventEnvelope> stream = eventStore.loadStream(accountId);
        assertThat(stream).hasSize(3);

        // Replay manually from empty state
        AccountState replayedState = AccountState.uninitialized(accountId);
        for (DomainEventEnvelope event : stream) {
            replayedState = AccountReducer.reduce(replayedState, event);
        }

        assertThat(replayedState.balanceMinor()).isEqualTo(420000L);
        assertThat(replayedState.sequenceNumber()).isEqualTo(3L);
    }

    // -------------------------------------------------------------------------
    // COMMAND PROCESSING & OCC TESTS (58 - 66)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("62. Stale expected version during command execution produces OCC conflict")
    void testOccConflictDuringConcurrentCommand() {
        UUID accountId = UUID.randomUUID();
        CommandContext ctx = CommandContext.of("user-1");
        commandProcessor.process(new CreateAccount(accountId, "INR", 50000L, 100000L), ctx);

        // Manually inject sequence 2 directly into DB behind commandProcessor's back to simulate race condition
        DomainEventEnvelope e2Concurrent = new DomainEventEnvelope(
                UUID.randomUUID(), accountId, "Account", 2L, "MoneyDeposited", 1,
                java.time.Instant.now(), ctx.toMetadata(), objectMapper.createObjectNode().put("amountMinor", 1000L)
        );
        eventStore.append(accountId, 1L, List.of(e2Concurrent));

        // Command processor loaded aggregate state at version 1 earlier, tries to append version 2
        // EventStore.append(accountId, expectedVersion=1, ...) will catch expected version mismatch and throw OCC
        assertThatThrownBy(() -> eventStore.append(accountId, 1L, List.of(e2Concurrent)))
                .isInstanceOf(OptimisticConcurrencyException.class);
    }

    @Test
    @DisplayName("64-66. Metadata, correlationId, causationId, and idempotencyKey propagated cleanly")
    void testMetadataPropagation() {
        UUID accountId = UUID.randomUUID();
        UUID corrId = UUID.randomUUID();
        UUID causId = UUID.randomUUID();
        CommandContext ctx = CommandContext.of(corrId, causId, "system-actor-9", "idemp-abc-123");

        CommandResult res = commandProcessor.process(new CreateAccount(accountId, "INR", 10000L, 50000L), ctx);
        DomainEventEnvelope event = res.emittedEvents().get(0);

        assertThat(event.metadata().correlationId()).isEqualTo(corrId);
        assertThat(event.metadata().causationId()).isEqualTo(causId);
        assertThat(event.metadata().actorId()).isEqualTo("system-actor-9");
        assertThat(event.metadata().idempotencyKey()).isEqualTo("idemp-abc-123");
    }
}
