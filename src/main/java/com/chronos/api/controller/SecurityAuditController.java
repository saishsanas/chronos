package com.chronos.api.controller;

import com.chronos.api.dto.SecurityAuditResponse;
import com.chronos.application.service.SecurityAuditService;
import com.chronos.domain.security.audit.SecurityAuditRecord;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/api/v1/ops/audit")
@Tag(name = "Security Audit", description = "Read-only access to the append-oriented operational security audit store")
@SecurityRequirement(name = "bearerAuth")
public class SecurityAuditController {

    private final SecurityAuditService auditService;

    public SecurityAuditController(SecurityAuditService auditService) {
        this.auditService = Objects.requireNonNull(auditService, "auditService must not be null");
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'AUDITOR')")
    @Operation(summary = "Get security audit logs", description = "Returns append-only security audit log entries with bounded pagination")
    public ResponseEntity<List<SecurityAuditResponse>> getAuditLogs(
        @RequestParam(defaultValue = "50") int limit,
        @RequestParam(defaultValue = "0") int offset,
        @RequestParam(required = false) String action,
        @RequestParam(required = false) String actor
    ) {
        int boundedLimit = Math.clamp(limit, 1, 100);
        int boundedOffset = Math.max(offset, 0);

        List<SecurityAuditRecord> records = auditService.getAuditLog(boundedLimit, boundedOffset, action, actor);
        List<SecurityAuditResponse> response = records.stream()
            .map(SecurityAuditResponse::fromDomain)
            .toList();

        return ResponseEntity.ok(response);
    }
}
