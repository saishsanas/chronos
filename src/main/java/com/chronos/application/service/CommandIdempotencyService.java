package com.chronos.application.service;

import com.chronos.api.dto.CommandExecutionResponse;
import com.chronos.application.port.CommandIdempotencyRepository;
import com.chronos.domain.idempotency.CommandIdempotencyConflictException;
import com.chronos.domain.idempotency.CommandIdempotencyRecord;
import com.chronos.domain.idempotency.IdempotencyStatus;
import com.chronos.infrastructure.observability.ChronosMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class CommandIdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(CommandIdempotencyService.class);

    private final CommandIdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;
    private final ChronosMetrics metrics;

    public CommandIdempotencyService(
        CommandIdempotencyRepository idempotencyRepository,
        ObjectMapper objectMapper,
        @Autowired(required = false) ChronosMetrics metrics
    ) {
        this.idempotencyRepository = Objects.requireNonNull(idempotencyRepository, "idempotencyRepository must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.metrics = metrics;
    }

    public CommandExecutionResponse executeIdempotent(
        String actorId,
        String idempotencyKey,
        Object requestPayload,
        String commandType,
        UUID aggregateId,
        Supplier<CommandExecutionResponse> commandSupplier
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return commandSupplier.get();
        }

        String effectiveActor = (actorId != null && !actorId.isBlank()) ? actorId : "ANONYMOUS";
        String requestHash = computeSha256Hash(requestPayload);

        // 1. Check existing record
        Optional<CommandIdempotencyRecord> existingOpt = idempotencyRepository.findByActorAndKey(effectiveActor, idempotencyKey);
        if (existingOpt.isPresent()) {
            return handleExistingRecord(existingOpt.get(), requestHash, idempotencyKey);
        }

        // 2. Try inserting IN_FLIGHT record
        CommandIdempotencyRecord inFlightRecord = CommandIdempotencyRecord.createInFlight(
            effectiveActor, idempotencyKey, requestHash, commandType, aggregateId
        );

        boolean inserted = idempotencyRepository.tryInsertInFlight(inFlightRecord);
        if (!inserted) {
            // Race condition: another concurrent request inserted first
            log.info("Concurrent insert race detected for idempotency key '{}'", idempotencyKey);
            Optional<CommandIdempotencyRecord> racedOpt = idempotencyRepository.findByActorAndKey(effectiveActor, idempotencyKey);
            if (racedOpt.isPresent()) {
                return handleExistingRecord(racedOpt.get(), requestHash, idempotencyKey);
            }
            throw new CommandIdempotencyConflictException("Concurrent request processing conflict for key: " + idempotencyKey);
        }

        // 3. Execute command
        try {
            CommandExecutionResponse response = commandSupplier.get();
            String responseJson = objectMapper.writeValueAsString(response);
            idempotencyRepository.markCompleted(inFlightRecord, 200, responseJson);
            return response;
        } catch (RuntimeException e) {
            idempotencyRepository.markFailed(inFlightRecord, e.getMessage());
            throw e;
        } catch (Exception e) {
            idempotencyRepository.markFailed(inFlightRecord, e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private CommandExecutionResponse handleExistingRecord(CommandIdempotencyRecord existing, String currentHash, String idempotencyKey) {
        if (existing.status() == IdempotencyStatus.COMPLETED) {
            if (existing.requestHash().equals(currentHash)) {
                log.info("Idempotent duplicate command detected for key '{}'. Returning cached result.", idempotencyKey);
                if (metrics != null) {
                    metrics.recordCommandIdempotencyDuplicate();
                }
                try {
                    return objectMapper.readValue(existing.responsePayload(), CommandExecutionResponse.class);
                } catch (Exception e) {
                    log.error("Failed to deserialize cached idempotency response payload for key {}", idempotencyKey, e);
                    throw new IllegalStateException("Failed to deserialize cached idempotency payload", e);
                }
            } else {
                log.warn("Idempotency conflict detected for key '{}': payload hash mismatch.", idempotencyKey);
                if (metrics != null) {
                    metrics.recordCommandIdempotencyConflict();
                }
                throw new CommandIdempotencyConflictException(
                    "Idempotency key '" + idempotencyKey + "' was previously used with a different request payload"
                );
            }
        } else if (existing.status() == IdempotencyStatus.IN_FLIGHT) {
            if (metrics != null) {
                metrics.recordCommandIdempotencyConflict();
            }
            throw new CommandIdempotencyConflictException(
                "A request with idempotency key '" + idempotencyKey + "' is currently in progress"
            );
        } else { // FAILED status allows retry with fresh execution
            log.info("Previous execution for idempotency key '{}' FAILED. Permitting retry.", idempotencyKey);
            if (!existing.requestHash().equals(currentHash)) {
                if (metrics != null) {
                    metrics.recordCommandIdempotencyConflict();
                }
                throw new CommandIdempotencyConflictException(
                    "Idempotency key '" + idempotencyKey + "' was previously used with a different request payload"
                );
            }
            return null; // Will trigger re-execution
        }
    }

    public String computeSha256Hash(Object payload) {
        if (payload == null) return "EMPTY_PAYLOAD";
        try {
            String json = objectMapper.writeValueAsString(payload);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm missing", e);
        } catch (Exception e) {
            return payload.toString();
        }
    }
}
