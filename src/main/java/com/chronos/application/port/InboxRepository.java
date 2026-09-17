package com.chronos.application.port;

import com.chronos.domain.inbox.InboxEventRecord;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface InboxRepository {

    Optional<InboxEventRecord> findByEventId(UUID eventId);

    InboxEventRecord save(InboxEventRecord record);

    void markProcessed(UUID inboxId, Instant processedAt);

    void markFailed(UUID inboxId, String lastError);

    long getLastProcessedSequence(UUID aggregateId);

    void updateConsumerSequence(UUID aggregateId, long sequenceNumber, Instant updatedAt);
}
