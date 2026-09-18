package com.chronos.api.controller;

import com.chronos.application.port.ProjectionRebuildJobRepository;
import com.chronos.application.service.ProjectionRebuildService;
import com.chronos.application.service.ProjectionVerificationService;
import com.chronos.domain.projection.ProjectionRebuildJob;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@CrossOrigin(origins = {"http://localhost:5173", "http://localhost:8080", "http://127.0.0.1:5173"})
@RestController
@RequestMapping("/api/v1/ops/projections")
@Tag(name = "Projection Operations & Maintenance", description = "Administrative operations for CQRS projection rebuilds, catch-up, and consistency verification")
public class AccountOpsController {

    private final ProjectionRebuildService rebuildService;
    private final ProjectionVerificationService verificationService;
    private final ProjectionRebuildJobRepository rebuildJobRepository;
    private final boolean opsEnabled;

    public AccountOpsController(
        ProjectionRebuildService rebuildService,
        ProjectionVerificationService verificationService,
        ProjectionRebuildJobRepository rebuildJobRepository,
        @Value("${chronos.ops.projection-rebuild.enabled:false}") boolean opsEnabled
    ) {
        this.rebuildService = Objects.requireNonNull(rebuildService, "rebuildService must not be null");
        this.verificationService = Objects.requireNonNull(verificationService, "verificationService must not be null");
        this.rebuildJobRepository = Objects.requireNonNull(rebuildJobRepository, "rebuildJobRepository must not be null");
        this.opsEnabled = opsEnabled;
    }

    @PostMapping("/rebuild")
    @Operation(summary = "Rebuild entire projection", description = "Reconstructs entire AccountSummary projection from Event Store using atomic staging cutover")
    public ResponseEntity<?> rebuildFull() {
        if (!opsEnabled) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Projection rebuild operations are disabled by configuration");
        }
        ProjectionRebuildJob job = rebuildService.rebuildFull();
        return ResponseEntity.ok(job);
    }

    @PostMapping("/rebuild/{accountId}")
    @Operation(summary = "Rebuild targeted account projection", description = "Reconstructs single account projection from Event Store")
    public ResponseEntity<?> rebuildTargeted(@PathVariable UUID accountId) {
        if (!opsEnabled) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Projection rebuild operations are disabled by configuration");
        }
        ProjectionRebuildJob job = rebuildService.rebuildTargeted(accountId);
        return ResponseEntity.ok(job);
    }

    @GetMapping("/verify")
    @Operation(summary = "Verify projection consistency", description = "Compares read model projections against authoritative Event Store state")
    public ResponseEntity<?> verifyAll() {
        if (!opsEnabled) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Projection verification operations are disabled by configuration");
        }
        ProjectionVerificationService.VerificationReport report = verificationService.verifyAll();
        return ResponseEntity.ok(report);
    }

    @GetMapping("/status")
    @Operation(summary = "Get latest rebuild job status", description = "Returns status of the most recent projection rebuild job")
    public ResponseEntity<?> getStatus() {
        Optional<ProjectionRebuildJob> jobOpt = rebuildJobRepository.findLatestForProjection("account_summary_projection");
        if (jobOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("No projection rebuild job history found");
        }
        return ResponseEntity.ok(jobOpt.get());
    }
}
