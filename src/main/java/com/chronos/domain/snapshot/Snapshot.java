package com.chronos.domain.snapshot;

import com.chronos.domain.account.AccountState;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Snapshot(
    UUID snapshotId,
    UUID aggregateId,
    long sequenceNumber,
    int snapshotVersion,
    int domainVersion,
    String replayLogicHash,
    Instant createdAt,
    AccountState state
) {
    public static final int CURRENT_SNAPSHOT_VERSION = 1;
    public static final int CURRENT_DOMAIN_VERSION = 1;
    public static final String CURRENT_REPLAY_LOGIC_HASH = "ACCOUNT_REPLAY_LOGIC_V1";

    public Snapshot {
        Objects.requireNonNull(snapshotId, "snapshotId must not be null");
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        if (sequenceNumber < 1) {
            throw new IllegalArgumentException("sequenceNumber must be >= 1");
        }
        if (snapshotVersion < 1) {
            throw new IllegalArgumentException("snapshotVersion must be >= 1");
        }
        if (domainVersion < 1) {
            throw new IllegalArgumentException("domainVersion must be >= 1");
        }
        Objects.requireNonNull(replayLogicHash, "replayLogicHash must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(state, "state must not be null");
    }

    public static Snapshot create(AccountState state) {
        Objects.requireNonNull(state, "state must not be null");
        return new Snapshot(
            UUID.randomUUID(),
            state.accountId(),
            state.sequenceNumber(),
            CURRENT_SNAPSHOT_VERSION,
            CURRENT_DOMAIN_VERSION,
            CURRENT_REPLAY_LOGIC_HASH,
            Instant.now(),
            state
        );
    }

    public boolean isValid() {
        return snapshotVersion == CURRENT_SNAPSHOT_VERSION &&
               domainVersion == CURRENT_DOMAIN_VERSION &&
               CURRENT_REPLAY_LOGIC_HASH.equals(replayLogicHash) &&
               state != null &&
               sequenceNumber == state.sequenceNumber();
    }
}
