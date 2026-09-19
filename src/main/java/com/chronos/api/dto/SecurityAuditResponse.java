package com.chronos.api.dto;

import com.chronos.domain.security.audit.SecurityAuditRecord;

import java.time.Instant;
import java.util.UUID;

public record SecurityAuditResponse(
    UUID auditId,
    Instant occurredAt,
    UUID actorUserId,
    String actorUsername,
    String action,
    String resourceType,
    String resourceId,
    String outcome,
    String correlationId,
    String details
) {
    public static SecurityAuditResponse fromDomain(SecurityAuditRecord record) {
        return new SecurityAuditResponse(
            record.auditId(),
            record.occurredAt(),
            record.actorUserId(),
            record.actorUsername(),
            record.action().name(),
            record.resourceType(),
            record.resourceId(),
            record.outcome(),
            record.correlationId(),
            record.detailsJson()
        );
    }
}
