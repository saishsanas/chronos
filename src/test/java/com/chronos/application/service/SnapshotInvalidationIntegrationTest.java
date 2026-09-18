package com.chronos.application.service;

import com.chronos.domain.account.AccountState;
import com.chronos.domain.account.AccountStatus;
import com.chronos.domain.snapshot.Snapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotInvalidationIntegrationTest {

    @Test
    @DisplayName("Valid snapshot passes isValid check")
    void validSnapshot() {
        UUID accountId = UUID.randomUUID();
        AccountState state = new AccountState(
            accountId, "USD", 1000L, 500L, 200L,
            AccountStatus.ACTIVE, 1L, Instant.now()
        );
        Snapshot snapshot = Snapshot.create(state);
        assertThat(snapshot.isValid()).isTrue();
    }

    @Test
    @DisplayName("Snapshot with outdated version or modified replay hash is invalid")
    void invalidSnapshotRejection() {
        UUID accountId = UUID.randomUUID();
        AccountState state = new AccountState(
            accountId, "USD", 1000L, 500L, 200L,
            AccountStatus.ACTIVE, 1L, Instant.now()
        );

        Snapshot invalidVersion = new Snapshot(
            UUID.randomUUID(), accountId, 1L, 99,
            Snapshot.CURRENT_DOMAIN_VERSION, Snapshot.CURRENT_REPLAY_LOGIC_HASH, Instant.now(), state
        );
        assertThat(invalidVersion.isValid()).isFalse();

        Snapshot invalidHash = new Snapshot(
            UUID.randomUUID(), accountId, 1L, Snapshot.CURRENT_SNAPSHOT_VERSION,
            Snapshot.CURRENT_DOMAIN_VERSION, "MODIFIED_REPLAY_HASH", Instant.now(), state
        );
        assertThat(invalidHash.isValid()).isFalse();
    }
}
