package com.chronos.application.port;

import com.chronos.domain.outbox.OutboxEventRecord;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxRepository {

    int recoverExpiredLeases(Instant now);

    List<OutboxEventRecord> claimDueBatch(int batchSize, String workerId, int leaseSeconds, Instant now);

    void markPublished(UUID outboxId, Instant publishedAt);

    void markForRetry(UUID outboxId, int attempts, Instant nextAttemptAt, String lastError);
}
