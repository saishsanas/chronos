package com.chronos.domain.event.upcasting;

import com.chronos.domain.event.upcasting.exception.UnknownEventTypeException;
import com.chronos.domain.event.upcasting.exception.UnsupportedEventVersionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventSchemaRegistryTest {

    private final EventSchemaRegistry registry = EventSchemaRegistry.getInstance();

    @Test
    @DisplayName("Catalog contains all 9 known account event types")
    void testAllNineEventTypesKnown() {
        Set<String> knownTypes = registry.getKnownEventTypes();
        assertThat(knownTypes).containsExactlyInAnyOrder(
            "AccountCreated",
            "MoneyDeposited",
            "MoneyWithdrawn",
            "AccountFrozen",
            "AccountUnfrozen",
            "OverdraftLimitChanged",
            "TransactionLimitChanged",
            "CorrectionIssued",
            "AccountClosed"
        );
        for (String type : knownTypes) {
            assertThat(registry.isKnownEventType(type)).isTrue();
        }
    }

    @Test
    @DisplayName("Unknown event type returns false and throws on access")
    void testUnknownEventType() {
        assertThat(registry.isKnownEventType("CryptoPurchased")).isFalse();
        assertThat(registry.isKnownEventType(null)).isFalse();

        assertThatThrownBy(() -> registry.getCurrentVersion("CryptoPurchased"))
            .isInstanceOf(UnknownEventTypeException.class)
            .hasMessageContaining("CryptoPurchased");

        assertThatThrownBy(() -> registry.validateVersion("CryptoPurchased", 1))
            .isInstanceOf(UnknownEventTypeException.class)
            .hasMessageContaining("CryptoPurchased");
    }

    @Test
    @DisplayName("MoneyDeposited has canonical version 2 and supports versions 1 and 2")
    void testMoneyDepositedVersionModel() {
        assertThat(registry.getCurrentVersion("MoneyDeposited")).isEqualTo(2);
        assertThat(registry.isSupportedVersion("MoneyDeposited", 1)).isTrue();
        assertThat(registry.isSupportedVersion("MoneyDeposited", 2)).isTrue();
        assertThat(registry.isSupportedVersion("MoneyDeposited", 3)).isFalse();
        assertThat(registry.isSupportedVersion("MoneyDeposited", 0)).isFalse();

        // Should not throw for supported versions
        registry.validateVersion("MoneyDeposited", 1);
        registry.validateVersion("MoneyDeposited", 2);
    }

    @Test
    @DisplayName("Other 8 event types have canonical version 1 and support only version 1")
    void testOtherEventsVersionModel() {
        String[] v1Events = {
            "AccountCreated", "MoneyWithdrawn", "AccountFrozen", "AccountUnfrozen",
            "OverdraftLimitChanged", "TransactionLimitChanged", "CorrectionIssued", "AccountClosed"
        };
        for (String eventType : v1Events) {
            assertThat(registry.getCurrentVersion(eventType)).isEqualTo(1);
            assertThat(registry.isSupportedVersion(eventType, 1)).isTrue();
            assertThat(registry.isSupportedVersion(eventType, 2)).isFalse();
            registry.validateVersion(eventType, 1);

            assertThatThrownBy(() -> registry.validateVersion(eventType, 2))
                .isInstanceOf(UnsupportedEventVersionException.class)
                .hasMessageContaining("exceeds current version 1");
        }
    }

    @Test
    @DisplayName("Future version exceeding current canonical version throws UnsupportedEventVersionException")
    void testFutureVersionRejected() {
        assertThatThrownBy(() -> registry.validateVersion("MoneyDeposited", 99))
            .isInstanceOf(UnsupportedEventVersionException.class)
            .hasMessageContaining("Unsupported future event version: 99 exceeds current version 2");

        assertThatThrownBy(() -> registry.validateVersion("AccountCreated", 2))
            .isInstanceOf(UnsupportedEventVersionException.class)
            .hasMessageContaining("Unsupported future event version: 2 exceeds current version 1");
    }

    @Test
    @DisplayName("Malformed version (< 1) throws UnsupportedEventVersionException")
    void testMalformedVersionRejected() {
        assertThatThrownBy(() -> registry.validateVersion("MoneyDeposited", 0))
            .isInstanceOf(UnsupportedEventVersionException.class)
            .hasMessageContaining("Event version must be >= 1");

        assertThatThrownBy(() -> registry.validateVersion("MoneyDeposited", -1))
            .isInstanceOf(UnsupportedEventVersionException.class)
            .hasMessageContaining("Event version must be >= 1");
    }
}
