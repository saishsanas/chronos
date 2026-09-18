package com.chronos.application.port;

import com.chronos.domain.idempotency.CommandIdempotencyRecord;

import java.util.Optional;

public interface CommandIdempotencyRepository {
    boolean tryInsertInFlight(CommandIdempotencyRecord record);
    Optional<CommandIdempotencyRecord> findByActorAndKey(String actorId, String idempotencyKey);
    void markCompleted(CommandIdempotencyRecord record, int responseStatus, String responsePayload);
    void markFailed(CommandIdempotencyRecord record, String errorDetails);
}
