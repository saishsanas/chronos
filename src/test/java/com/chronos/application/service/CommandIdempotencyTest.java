package com.chronos.application.service;

import com.chronos.api.dto.CreateAccountRequest;
import com.chronos.application.port.CommandIdempotencyRepository;
import com.chronos.domain.idempotency.CommandIdempotencyConflictException;
import com.chronos.domain.idempotency.CommandIdempotencyRecord;
import com.chronos.domain.idempotency.IdempotencyStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CommandIdempotencyTest {

    private CommandIdempotencyRepository idempotencyRepository;
    private ObjectMapper objectMapper;
    private CommandIdempotencyService idempotencyService;

    @BeforeEach
    void setUp() {
        idempotencyRepository = Mockito.mock(CommandIdempotencyRepository.class);
        objectMapper = new ObjectMapper();
        idempotencyService = new CommandIdempotencyService(idempotencyRepository, objectMapper, null);
    }

    @Test
    @DisplayName("Should execute command directly when idempotencyKey is null or blank")
    void executeWithoutIdempotencyKey() {
        var response = idempotencyService.executeIdempotent(
            "actor1", null, new CreateAccountRequest("USD", 1000L, 500L), "CreateAccount", UUID.randomUUID(),
            () -> null
        );
        verifyNoInteractions(idempotencyRepository);
    }

    @Test
    @DisplayName("Should throw CommandIdempotencyConflictException when key reused with different request payload")
    void reuseKeyWithDifferentPayload() {
        String actor = "actor1";
        String key = "key-123";
        CreateAccountRequest req1 = new CreateAccountRequest("USD", 1000L, 500L);
        CreateAccountRequest req2 = new CreateAccountRequest("EUR", 2000L, 1000L);

        String hash1 = idempotencyService.computeSha256Hash(req1);

        CommandIdempotencyRecord existingRecord = new CommandIdempotencyRecord(
            UUID.randomUUID(), actor, key, hash1, "CreateAccount", UUID.randomUUID(),
            IdempotencyStatus.COMPLETED, 200, "{}", Instant.now(), Instant.now(), null
        );

        when(idempotencyRepository.findByActorAndKey(actor, key)).thenReturn(Optional.of(existingRecord));

        assertThatThrownBy(() ->
            idempotencyService.executeIdempotent(
                actor, key, req2, "CreateAccount", UUID.randomUUID(),
                () -> null
            )
        ).isInstanceOf(CommandIdempotencyConflictException.class)
         .hasMessageContaining("previously used with a different request payload");
    }

    @Test
    @DisplayName("Should throw CommandIdempotencyConflictException when key is IN_FLIGHT")
    void keyInFlight() {
        String actor = "actor1";
        String key = "key-123";
        CreateAccountRequest req = new CreateAccountRequest("USD", 1000L, 500L);
        String hash = idempotencyService.computeSha256Hash(req);

        CommandIdempotencyRecord existingRecord = new CommandIdempotencyRecord(
            UUID.randomUUID(), actor, key, hash, "CreateAccount", UUID.randomUUID(),
            IdempotencyStatus.IN_FLIGHT, null, null, Instant.now(), null, null
        );

        when(idempotencyRepository.findByActorAndKey(actor, key)).thenReturn(Optional.of(existingRecord));

        assertThatThrownBy(() ->
            idempotencyService.executeIdempotent(
                actor, key, req, "CreateAccount", UUID.randomUUID(),
                () -> null
            )
        ).isInstanceOf(CommandIdempotencyConflictException.class)
         .hasMessageContaining("currently in progress");
    }
}
