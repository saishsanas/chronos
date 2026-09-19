package com.chronos.api.controller;

import com.chronos.api.dto.*;
import com.chronos.application.command.CommandContext;
import com.chronos.application.command.CommandResult;
import com.chronos.application.model.TemporalResult;
import com.chronos.application.port.EventStore;
import com.chronos.application.service.AccountCommandProcessor;
import com.chronos.application.service.AccountSummaryQueryService;
import com.chronos.application.service.TemporalStateReconstructor;
import com.chronos.application.service.CommandIdempotencyService;
import com.chronos.application.service.SecurityAuditService;
import com.chronos.domain.account.AccountStatus;
import com.chronos.domain.account.command.*;
import com.chronos.domain.account.exception.AccountNotFoundException;
import com.chronos.domain.event.DomainEventEnvelope;
import com.chronos.infrastructure.security.SecurityPrincipalAccessor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts")
@Tag(name = "Account Temporal API", description = "Commands, Current State, Historical Replay, CQRS Read Model, and Event History APIs for Chronos Engine")
@SecurityRequirement(name = "bearerAuth")
public class AccountController {

    private final AccountCommandProcessor commandProcessor;
    private final TemporalStateReconstructor temporalReconstructor;
    private final EventStore eventStore;
    private final AccountSummaryQueryService summaryQueryService;
    private final CommandIdempotencyService idempotencyService;
    private final SecurityPrincipalAccessor principalAccessor;
    private final SecurityAuditService auditService;

    public AccountController(
        AccountCommandProcessor commandProcessor,
        TemporalStateReconstructor temporalReconstructor,
        EventStore eventStore,
        AccountSummaryQueryService summaryQueryService,
        CommandIdempotencyService idempotencyService,
        SecurityPrincipalAccessor principalAccessor,
        SecurityAuditService auditService
    ) {
        this.commandProcessor = Objects.requireNonNull(commandProcessor, "commandProcessor must not be null");
        this.temporalReconstructor = Objects.requireNonNull(temporalReconstructor, "temporalReconstructor must not be null");
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore must not be null");
        this.summaryQueryService = Objects.requireNonNull(summaryQueryService, "summaryQueryService must not be null");
        this.idempotencyService = Objects.requireNonNull(idempotencyService, "idempotencyService must not be null");
        this.principalAccessor = Objects.requireNonNull(principalAccessor, "principalAccessor must not be null");
        this.auditService = Objects.requireNonNull(auditService, "auditService must not be null");
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    @Operation(summary = "Create account", description = "Initializes a new bank account with currency and limits")
    public ResponseEntity<CommandExecutionResponse> createAccount(
        @Valid @RequestBody CreateAccountRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
        @RequestHeader(value = "X-Causation-Id", required = false) String causationId
    ) {
        UUID accountId = UUID.randomUUID();
        CommandContext context = createContext(idempotencyKey, correlationId, causationId);
        CreateAccount command = new CreateAccount(accountId, request.currency(), request.initialOverdraftLimitMinor(), request.initialTransactionLimitMinor());
        CommandExecutionResponse response = idempotencyService.executeIdempotent(
            context.actorId(), idempotencyKey, request, "CreateAccount", accountId,
            () -> CommandExecutionResponse.fromDomain(commandProcessor.process(command, context), context.correlationId())
        );
        principalAccessor.getPrincipal().ifPresent(p ->
            auditService.recordAccountCommand(p, "CreateAccount", accountId, context.correlationId())
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{accountId}/deposits")
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    @Operation(summary = "Deposit money", description = "Appends MoneyDeposited event to aggregate stream")
    public ResponseEntity<CommandExecutionResponse> deposit(
        @PathVariable UUID accountId,
        @Valid @RequestBody DepositRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
        @RequestHeader(value = "X-Causation-Id", required = false) String causationId
    ) {
        CommandContext context = createContext(idempotencyKey, correlationId, causationId);
        String source = (request.source() != null && !request.source().isBlank()) ? request.source().trim() : "MANUAL";
        DepositMoney command = new DepositMoney(accountId, request.amountMinor(), source);
        CommandExecutionResponse response = idempotencyService.executeIdempotent(
            context.actorId(), idempotencyKey, request, "DepositMoney", accountId,
            () -> CommandExecutionResponse.fromDomain(commandProcessor.process(command, context), context.correlationId())
        );
        principalAccessor.getPrincipal().ifPresent(p ->
            auditService.recordAccountCommand(p, "DepositMoney", accountId, context.correlationId())
        );
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{accountId}/withdrawals")
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    @Operation(summary = "Withdraw money", description = "Appends MoneyWithdrawn event to aggregate stream")
    public ResponseEntity<CommandExecutionResponse> withdraw(
        @PathVariable UUID accountId,
        @Valid @RequestBody WithdrawalRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
        @RequestHeader(value = "X-Causation-Id", required = false) String causationId
    ) {
        CommandContext context = createContext(idempotencyKey, correlationId, causationId);
        WithdrawMoney command = new WithdrawMoney(accountId, request.amountMinor());
        CommandExecutionResponse response = idempotencyService.executeIdempotent(
            context.actorId(), idempotencyKey, request, "WithdrawMoney", accountId,
            () -> CommandExecutionResponse.fromDomain(commandProcessor.process(command, context), context.correlationId())
        );
        principalAccessor.getPrincipal().ifPresent(p ->
            auditService.recordAccountCommand(p, "WithdrawMoney", accountId, context.correlationId())
        );
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{accountId}/freeze")
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    @Operation(summary = "Freeze account", description = "Appends AccountFrozen event to aggregate stream")
    public ResponseEntity<CommandExecutionResponse> freeze(
        @PathVariable UUID accountId,
        @Valid @RequestBody FreezeAccountRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
        @RequestHeader(value = "X-Causation-Id", required = false) String causationId
    ) {
        CommandContext context = createContext(idempotencyKey, correlationId, causationId);
        FreezeAccount command = new FreezeAccount(accountId, request.reason());
        CommandExecutionResponse response = idempotencyService.executeIdempotent(
            context.actorId(), idempotencyKey, request, "FreezeAccount", accountId,
            () -> CommandExecutionResponse.fromDomain(commandProcessor.process(command, context), context.correlationId())
        );
        principalAccessor.getPrincipal().ifPresent(p ->
            auditService.recordAccountCommand(p, "FreezeAccount", accountId, context.correlationId())
        );
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{accountId}/unfreeze")
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    @Operation(summary = "Unfreeze account", description = "Appends AccountUnfrozen event to aggregate stream")
    public ResponseEntity<CommandExecutionResponse> unfreeze(
        @PathVariable UUID accountId,
        @Valid @RequestBody UnfreezeAccountRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
        @RequestHeader(value = "X-Causation-Id", required = false) String causationId
    ) {
        CommandContext context = createContext(idempotencyKey, correlationId, causationId);
        UnfreezeAccount command = new UnfreezeAccount(accountId, request.reason());
        CommandExecutionResponse response = idempotencyService.executeIdempotent(
            context.actorId(), idempotencyKey, request, "UnfreezeAccount", accountId,
            () -> CommandExecutionResponse.fromDomain(commandProcessor.process(command, context), context.correlationId())
        );
        principalAccessor.getPrincipal().ifPresent(p ->
            auditService.recordAccountCommand(p, "UnfreezeAccount", accountId, context.correlationId())
        );
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{accountId}/limits/overdraft")
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    @Operation(summary = "Set overdraft limit", description = "Appends OverdraftLimitChanged event to aggregate stream")
    public ResponseEntity<CommandExecutionResponse> setOverdraftLimit(
        @PathVariable UUID accountId,
        @Valid @RequestBody ChangeOverdraftLimitRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
        @RequestHeader(value = "X-Causation-Id", required = false) String causationId
    ) {
        CommandContext context = createContext(idempotencyKey, correlationId, causationId);
        SetOverdraftLimit command = new SetOverdraftLimit(accountId, request.newOverdraftLimitMinor());
        CommandExecutionResponse response = idempotencyService.executeIdempotent(
            context.actorId(), idempotencyKey, request, "SetOverdraftLimit", accountId,
            () -> CommandExecutionResponse.fromDomain(commandProcessor.process(command, context), context.correlationId())
        );
        principalAccessor.getPrincipal().ifPresent(p ->
            auditService.recordAccountCommand(p, "SetOverdraftLimit", accountId, context.correlationId())
        );
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{accountId}/limits/transaction")
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    @Operation(summary = "Set transaction limit", description = "Appends TransactionLimitChanged event to aggregate stream")
    public ResponseEntity<CommandExecutionResponse> setTransactionLimit(
        @PathVariable UUID accountId,
        @Valid @RequestBody ChangeTransactionLimitRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
        @RequestHeader(value = "X-Causation-Id", required = false) String causationId
    ) {
        CommandContext context = createContext(idempotencyKey, correlationId, causationId);
        SetTransactionLimit command = new SetTransactionLimit(accountId, request.newTransactionLimitMinor());
        CommandExecutionResponse response = idempotencyService.executeIdempotent(
            context.actorId(), idempotencyKey, request, "SetTransactionLimit", accountId,
            () -> CommandExecutionResponse.fromDomain(commandProcessor.process(command, context), context.correlationId())
        );
        principalAccessor.getPrincipal().ifPresent(p ->
            auditService.recordAccountCommand(p, "SetTransactionLimit", accountId, context.correlationId())
        );
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{accountId}/corrections")
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    @Operation(summary = "Issue correction", description = "Appends CorrectionIssued event to aggregate stream")
    public ResponseEntity<CommandExecutionResponse> issueCorrection(
        @PathVariable UUID accountId,
        @Valid @RequestBody IssueCorrectionRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
        @RequestHeader(value = "X-Causation-Id", required = false) String causationId
    ) {
        CommandContext context = createContext(idempotencyKey, correlationId, causationId);
        IssueCorrection command = new IssueCorrection(
            accountId, request.targetEventId(), request.correctionType(), request.direction(), request.adjustmentAmountMinor(), request.reason()
        );
        CommandExecutionResponse response = idempotencyService.executeIdempotent(
            context.actorId(), idempotencyKey, request, "IssueCorrection", accountId,
            () -> CommandExecutionResponse.fromDomain(commandProcessor.process(command, context), context.correlationId())
        );
        principalAccessor.getPrincipal().ifPresent(p ->
            auditService.recordAccountCommand(p, "IssueCorrection", accountId, context.correlationId())
        );
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{accountId}/close")
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    @Operation(summary = "Close account", description = "Appends AccountClosed event to aggregate stream")
    public ResponseEntity<CommandExecutionResponse> close(
        @PathVariable UUID accountId,
        @Valid @RequestBody CloseAccountRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
        @RequestHeader(value = "X-Causation-Id", required = false) String causationId
    ) {
        CommandContext context = createContext(idempotencyKey, correlationId, causationId);
        CloseAccount command = new CloseAccount(accountId, request.reason());
        CommandExecutionResponse response = idempotencyService.executeIdempotent(
            context.actorId(), idempotencyKey, request, "CloseAccount", accountId,
            () -> CommandExecutionResponse.fromDomain(commandProcessor.process(command, context), context.correlationId())
        );
        principalAccessor.getPrincipal().ifPresent(p ->
            auditService.recordAccountCommand(p, "CloseAccount", accountId, context.correlationId())
        );
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{accountId}")
    @PreAuthorize("hasAnyRole('AUDITOR', 'OPERATOR', 'ADMIN')")
    @Operation(summary = "Get current account state", description = "Reconstructs current account state from snapshots and event stream")
    public ResponseEntity<AccountStateResponse> getCurrentState(@PathVariable UUID accountId) {
        TemporalResult result = temporalReconstructor.reconstructCurrentState(accountId);
        if (result.reconstructedState().status() == AccountStatus.UNINITIALIZED && result.sequenceNumber() == 0) {
            throw new AccountNotFoundException(accountId);
        }
        return ResponseEntity.ok(AccountStateResponse.fromDomain(result.reconstructedState()));
    }

    @GetMapping("/{accountId}/state-at")
    @PreAuthorize("hasAnyRole('AUDITOR', 'OPERATOR', 'ADMIN')")
    @Operation(summary = "Get historical account state at timestamp T", description = "Reconstructs state for recordedAt <= T using inclusive temporal replay")
    public ResponseEntity<TemporalStateResponse> getStateAt(
        @PathVariable UUID accountId,
        @Parameter(description = "ISO-8601 target timestamp T")
        @RequestParam("at") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant at
    ) {
        if (eventStore.loadStream(accountId).isEmpty()) {
            throw new AccountNotFoundException(accountId);
        }
        TemporalResult result = temporalReconstructor.reconstructStateAt(accountId, at);
        return ResponseEntity.ok(TemporalStateResponse.fromDomain(result));
    }

    @GetMapping("/{accountId}/events")
    @PreAuthorize("hasAnyRole('AUDITOR', 'OPERATOR', 'ADMIN')")
    @Operation(summary = "Get chronological event history", description = "Returns immutable stream of domain events ordered by sequenceNumber ASC")
    public ResponseEntity<List<EventEnvelopeResponse>> getEventHistory(@PathVariable UUID accountId) {
        List<DomainEventEnvelope> stream = eventStore.loadStream(accountId);
        if (stream.isEmpty()) {
            throw new AccountNotFoundException(accountId);
        }
        List<EventEnvelopeResponse> responses = stream.stream()
            .map(EventEnvelopeResponse::fromDomain)
            .toList();
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/{accountId}/summary")
    @PreAuthorize("hasAnyRole('AUDITOR', 'OPERATOR', 'ADMIN')")
    @Operation(summary = "Get CQRS account summary", description = "Returns fast read-model summary via Redis cache with PostgreSQL projection fallback")
    public ResponseEntity<AccountSummaryResponse> getAccountSummary(@PathVariable UUID accountId) {
        AccountSummaryResponse response = summaryQueryService.getAccountSummary(accountId);
        return ResponseEntity.ok(response);
    }

    private CommandContext createContext(String idempotencyHeader, String correlationHeader, String causationHeader) {
        UUID correlationId = parseOrGenerateUuid(correlationHeader);
        UUID causationId = parseOrGenerateUuid(causationHeader);
        String actorId = principalAccessor.getPrincipal()
            .map(p -> p.userId().toString())
            .orElse("ANONYMOUS");
        return new CommandContext(correlationId, causationId, actorId, idempotencyHeader);
    }

    private UUID parseOrGenerateUuid(String headerValue) {
        if (headerValue != null && !headerValue.isBlank()) {
            try {
                return UUID.fromString(headerValue.trim());
            } catch (IllegalArgumentException e) {
                return UUID.randomUUID();
            }
        }
        return UUID.randomUUID();
    }
}
