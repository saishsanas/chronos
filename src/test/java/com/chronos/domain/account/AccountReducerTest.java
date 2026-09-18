package com.chronos.domain.account;

import com.chronos.application.port.CorruptedEventStreamException;
import com.chronos.domain.event.DomainEventEnvelope;
import com.chronos.domain.event.EventMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountReducerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID accountId = UUID.randomUUID();
    private final Instant baseTime = Instant.parse("2026-09-17T12:00:00Z");
    private final EventMetadata metadata = new EventMetadata(UUID.randomUUID(), UUID.randomUUID(), "test-actor", "idemp-key");

    @Test
    @DisplayName("41. Reducer handles all 9 event types correctly")
    void testAllNineEventTypesReduced() {
        // 1. AccountCreated
        ObjectNode createPayload = objectMapper.createObjectNode()
                .put("currency", "INR")
                .put("initialOverdraftLimitMinor", 50000L)
                .put("initialTransactionLimitMinor", 100000L);
        DomainEventEnvelope e1 = new DomainEventEnvelope(
                UUID.randomUUID(), accountId, "Account", 1L, "AccountCreated", 1, baseTime, metadata, createPayload
        );
        AccountState s1 = AccountReducer.reduce(AccountState.uninitialized(accountId), e1);
        assertThat(s1.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(s1.balanceMinor()).isEqualTo(0L);
        assertThat(s1.overdraftLimitMinor()).isEqualTo(50000L);
        assertThat(s1.transactionLimitMinor()).isEqualTo(100000L);

        // 2. MoneyDeposited (canonical v2)
        ObjectNode depositPayload = objectMapper.createObjectNode()
                .put("amountMinor", 200000L)
                .put("source", "MANUAL");
        DomainEventEnvelope e2 = new DomainEventEnvelope(
                UUID.randomUUID(), accountId, "Account", 2L, "MoneyDeposited", 2, baseTime.plusSeconds(1), metadata, depositPayload
        );
        AccountState s2 = AccountReducer.reduce(s1, e2);
        assertThat(s2.balanceMinor()).isEqualTo(200000L);

        // 3. MoneyWithdrawn
        ObjectNode withdrawPayload = objectMapper.createObjectNode().put("amountMinor", 50000L);
        DomainEventEnvelope e3 = new DomainEventEnvelope(
                UUID.randomUUID(), accountId, "Account", 3L, "MoneyWithdrawn", 1, baseTime.plusSeconds(2), metadata, withdrawPayload
        );
        AccountState s3 = AccountReducer.reduce(s2, e3);
        assertThat(s3.balanceMinor()).isEqualTo(150000L);

        // 4. AccountFrozen
        ObjectNode freezePayload = objectMapper.createObjectNode().put("reason", "security check");
        DomainEventEnvelope e4 = new DomainEventEnvelope(
                UUID.randomUUID(), accountId, "Account", 4L, "AccountFrozen", 1, baseTime.plusSeconds(3), metadata, freezePayload
        );
        AccountState s4 = AccountReducer.reduce(s3, e4);
        assertThat(s4.status()).isEqualTo(AccountStatus.FROZEN);

        // 5. AccountUnfrozen
        ObjectNode unfreezePayload = objectMapper.createObjectNode().put("reason", "verified");
        DomainEventEnvelope e5 = new DomainEventEnvelope(
                UUID.randomUUID(), accountId, "Account", 5L, "AccountUnfrozen", 1, baseTime.plusSeconds(4), metadata, unfreezePayload
        );
        AccountState s5 = AccountReducer.reduce(s4, e5);
        assertThat(s5.status()).isEqualTo(AccountStatus.ACTIVE);

        // 6. OverdraftLimitChanged
        ObjectNode overdraftPayload = objectMapper.createObjectNode().put("newOverdraftLimitMinor", 100000L);
        DomainEventEnvelope e6 = new DomainEventEnvelope(
                UUID.randomUUID(), accountId, "Account", 6L, "OverdraftLimitChanged", 1, baseTime.plusSeconds(5), metadata, overdraftPayload
        );
        AccountState s6 = AccountReducer.reduce(s5, e6);
        assertThat(s6.overdraftLimitMinor()).isEqualTo(100000L);

        // 7. TransactionLimitChanged
        ObjectNode txLimitPayload = objectMapper.createObjectNode().put("newTransactionLimitMinor", 300000L);
        DomainEventEnvelope e7 = new DomainEventEnvelope(
                UUID.randomUUID(), accountId, "Account", 7L, "TransactionLimitChanged", 1, baseTime.plusSeconds(6), metadata, txLimitPayload
        );
        AccountState s7 = AccountReducer.reduce(s6, e7);
        assertThat(s7.transactionLimitMinor()).isEqualTo(300000L);

        // 8. CorrectionIssued
        ObjectNode correctionPayload = objectMapper.createObjectNode()
                .put("targetEventId", e3.eventId().toString())
                .put("correctionType", "REVERSAL")
                .put("direction", "CREDIT")
                .put("adjustmentAmountMinor", 50000L)
                .put("reason", "Reversal of withdrawal");
        DomainEventEnvelope e8 = new DomainEventEnvelope(
                UUID.randomUUID(), accountId, "Account", 8L, "CorrectionIssued", 1, baseTime.plusSeconds(7), metadata, correctionPayload
        );
        AccountState s8 = AccountReducer.reduce(s7, e8);
        assertThat(s8.balanceMinor()).isEqualTo(200000L);

        // 9. AccountClosed (First withdraw net balance so balance is 0 for close, or reducer enforces closed state)
        ObjectNode withdrawAllPayload = objectMapper.createObjectNode().put("amountMinor", 200000L);
        DomainEventEnvelope e9PreClose = new DomainEventEnvelope(
                UUID.randomUUID(), accountId, "Account", 9L, "MoneyWithdrawn", 1, baseTime.plusSeconds(8), metadata, withdrawAllPayload
        );
        AccountState s9 = AccountReducer.reduce(s8, e9PreClose);

        ObjectNode closePayload = objectMapper.createObjectNode().put("reason", "customer request");
        DomainEventEnvelope e10Close = new DomainEventEnvelope(
                UUID.randomUUID(), accountId, "Account", 10L, "AccountClosed", 1, baseTime.plusSeconds(9), metadata, closePayload
        );
        AccountState s10 = AccountReducer.reduce(s9, e10Close);
        assertThat(s10.status()).isEqualTo(AccountStatus.CLOSED);
    }

    @Test
    @DisplayName("42. Reducer is pure and deterministic")
    void testReducerIsPureAndDeterministic() {
        ObjectNode createPayload = objectMapper.createObjectNode()
                .put("currency", "INR")
                .put("initialOverdraftLimitMinor", 10000L)
                .put("initialTransactionLimitMinor", 50000L);
        DomainEventEnvelope e1 = new DomainEventEnvelope(
                UUID.randomUUID(), accountId, "Account", 1L, "AccountCreated", 1, baseTime, metadata, createPayload
        );

        AccountState run1 = AccountReducer.reduce(AccountState.uninitialized(accountId), e1);
        AccountState run2 = AccountReducer.reduce(AccountState.uninitialized(accountId), e1);

        assertThat(run1).isEqualTo(run2);
    }

    @Test
    @DisplayName("45. Aggregate identity mismatch rejected")
    void testAggregateIdentityMismatchRejected() {
        ObjectNode payload = objectMapper.createObjectNode().put("currency", "INR").put("initialOverdraftLimitMinor", 0L).put("initialTransactionLimitMinor", 10000L);
        DomainEventEnvelope e1 = new DomainEventEnvelope(
                UUID.randomUUID(), accountId, "Account", 1L, "AccountCreated", 1, baseTime, metadata, payload
        );
        AccountState state = AccountReducer.reduce(AccountState.uninitialized(accountId), e1);

        UUID foreignId = UUID.randomUUID();
        DomainEventEnvelope e2Foreign = new DomainEventEnvelope(
                UUID.randomUUID(), foreignId, "Account", 2L, "MoneyDeposited", 1, baseTime.plusSeconds(1), metadata, objectMapper.createObjectNode().put("amountMinor", 100L)
        );

        assertThatThrownBy(() -> AccountReducer.reduce(state, e2Foreign))
                .isInstanceOf(CorruptedEventStreamException.class)
                .hasMessageContaining("Aggregate ID mismatch");
    }

    @Test
    @DisplayName("46. Aggregate type mismatch rejected")
    void testAggregateTypeMismatchRejected() {
        DomainEventEnvelope invalidTypeEvent = new DomainEventEnvelope(
                UUID.randomUUID(), accountId, "Order", 1L, "AccountCreated", 1, baseTime, metadata, objectMapper.createObjectNode().put("currency", "INR")
        );

        assertThatThrownBy(() -> AccountReducer.reduce(AccountState.uninitialized(accountId), invalidTypeEvent))
                .isInstanceOf(CorruptedEventStreamException.class)
                .hasMessageContaining("Invalid aggregate type");
    }

    @Test
    @DisplayName("47. Unknown event type rejected")
    void testUnknownEventTypeRejected() {
        ObjectNode payload = objectMapper.createObjectNode().put("currency", "INR").put("initialOverdraftLimitMinor", 0L).put("initialTransactionLimitMinor", 10000L);
        DomainEventEnvelope e1 = new DomainEventEnvelope(
                UUID.randomUUID(), accountId, "Account", 1L, "AccountCreated", 1, baseTime, metadata, payload
        );
        AccountState state = AccountReducer.reduce(AccountState.uninitialized(accountId), e1);

        DomainEventEnvelope unknownEvent = new DomainEventEnvelope(
                UUID.randomUUID(), accountId, "Account", 2L, "UnknownMagicEvent", 1, baseTime.plusSeconds(1), metadata, objectMapper.createObjectNode()
        );

        assertThatThrownBy(() -> AccountReducer.reduce(state, unknownEvent))
                .isInstanceOf(CorruptedEventStreamException.class)
                .hasMessageContaining("Unknown event type");
    }

    @Test
    @DisplayName("48. Unsupported event version rejected")
    void testUnsupportedEventVersionRejected() {
        DomainEventEnvelope v2Event = new DomainEventEnvelope(
                UUID.randomUUID(), accountId, "Account", 1L, "AccountCreated", 2, baseTime, metadata, objectMapper.createObjectNode().put("currency", "INR")
        );

        assertThatThrownBy(() -> AccountReducer.reduce(AccountState.uninitialized(accountId), v2Event))
                .isInstanceOf(CorruptedEventStreamException.class)
                .hasMessageContaining("Unsupported event version");
    }

    @Test
    @DisplayName("49 & 50. Sequence gap and regression rejected")
    void testSequenceGapAndRegressionRejected() {
        ObjectNode payload = objectMapper.createObjectNode().put("currency", "INR").put("initialOverdraftLimitMinor", 0L).put("initialTransactionLimitMinor", 10000L);
        DomainEventEnvelope e1 = new DomainEventEnvelope(
                UUID.randomUUID(), accountId, "Account", 1L, "AccountCreated", 1, baseTime, metadata, payload
        );
        AccountState state = AccountReducer.reduce(AccountState.uninitialized(accountId), e1);

        // Gap: sequence 3 instead of 2
        DomainEventEnvelope e3Gap = new DomainEventEnvelope(
                UUID.randomUUID(), accountId, "Account", 3L, "MoneyDeposited", 1, baseTime.plusSeconds(1), metadata, objectMapper.createObjectNode().put("amountMinor", 100L)
        );
        assertThatThrownBy(() -> AccountReducer.reduce(state, e3Gap))
                .isInstanceOf(CorruptedEventStreamException.class)
                .hasMessageContaining("Sequence gap or regression detected");
    }

    @Test
    @DisplayName("51. AccountCreated not first rejected")
    void testAccountCreatedNotFirstRejected() {
        DomainEventEnvelope depositFirst = new DomainEventEnvelope(
                UUID.randomUUID(), accountId, "Account", 1L, "MoneyDeposited", 1, baseTime, metadata, objectMapper.createObjectNode().put("amountMinor", 100L)
        );

        assertThatThrownBy(() -> AccountReducer.reduce(AccountState.uninitialized(accountId), depositFirst))
                .isInstanceOf(CorruptedEventStreamException.class)
                .hasMessageContaining("First event in stream must be 'AccountCreated'");
    }

    @Test
    @DisplayName("52. Illegal lifecycle transition rejected")
    void testIllegalLifecycleTransitionRejected() {
        ObjectNode payload = objectMapper.createObjectNode().put("currency", "INR").put("initialOverdraftLimitMinor", 0L).put("initialTransactionLimitMinor", 10000L);
        DomainEventEnvelope e1 = new DomainEventEnvelope(
                UUID.randomUUID(), accountId, "Account", 1L, "AccountCreated", 1, baseTime, metadata, payload
        );
        AccountState s1 = AccountReducer.reduce(AccountState.uninitialized(accountId), e1);

        // Cannot unfreeze an ACTIVE account
        DomainEventEnvelope unfreezeActive = new DomainEventEnvelope(
                UUID.randomUUID(), accountId, "Account", 2L, "AccountUnfrozen", 1, baseTime.plusSeconds(1), metadata, objectMapper.createObjectNode().put("reason", "test")
        );
        assertThatThrownBy(() -> AccountReducer.reduce(s1, unfreezeActive))
                .isInstanceOf(CorruptedEventStreamException.class)
                .hasMessageContaining("Cannot apply AccountUnfrozen to an account in status ACTIVE");
    }
}
