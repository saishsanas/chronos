package com.chronos.domain.security.audit;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record SecurityAuditRecord(
    UUID auditId,
    Instant occurredAt,
    UUID actorUserId,
    String actorUsername,
    SecurityAuditAction action,
    String resourceType,
    String resourceId,
    String outcome,
    String correlationId,
    String detailsJson
) {
    public SecurityAuditRecord {
        Objects.requireNonNull(auditId, "auditId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(resourceType, "resourceType must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
    }

    public static SecurityAuditRecord loginSuccess(UUID userId, String username, String correlationId, String detailsJson) {
        return new SecurityAuditRecord(
            UUID.randomUUID(),
            Instant.now(),
            userId,
            username,
            SecurityAuditAction.LOGIN_SUCCESS,
            "AUTHENTICATION",
            username,
            "SUCCESS",
            correlationId,
            detailsJson
        );
    }

    public static SecurityAuditRecord loginFailure(String attemptedUsername, String correlationId, String reason) {
        return new SecurityAuditRecord(
            UUID.randomUUID(),
            Instant.now(),
            null,
            attemptedUsername,
            SecurityAuditAction.LOGIN_FAILURE,
            "AUTHENTICATION",
            attemptedUsername,
            "FAILURE",
            correlationId,
            reason != null ? "{\"reason\":\"" + reason + "\"}" : null
        );
    }

    public static SecurityAuditRecord accountCommand(
        UUID actorUserId,
        String actorUsername,
        String commandType,
        UUID accountId,
        String correlationId,
        String outcome,
        String detailsJson
    ) {
        return new SecurityAuditRecord(
            UUID.randomUUID(),
            Instant.now(),
            actorUserId,
            actorUsername,
            SecurityAuditAction.ACCOUNT_COMMAND,
            "ACCOUNT",
            accountId != null ? accountId.toString() : null,
            outcome,
            correlationId,
            detailsJson
        );
    }

    public static SecurityAuditRecord projectionRebuild(
        UUID actorUserId,
        String actorUsername,
        String scope,
        String jobId,
        String correlationId,
        String outcome
    ) {
        return new SecurityAuditRecord(
            UUID.randomUUID(),
            Instant.now(),
            actorUserId,
            actorUsername,
            SecurityAuditAction.PROJECTION_REBUILD,
            "PROJECTION",
            jobId,
            outcome,
            correlationId,
            scope != null ? "{\"scope\":\"" + scope + "\"}" : null
        );
    }

    public static SecurityAuditRecord securityDenied(
        UUID actorUserId,
        String actorUsername,
        String resourceType,
        String resourceId,
        String correlationId,
        String reason
    ) {
        return new SecurityAuditRecord(
            UUID.randomUUID(),
            Instant.now(),
            actorUserId,
            actorUsername,
            SecurityAuditAction.SECURITY_DENIED,
            resourceType,
            resourceId,
            "DENIED",
            correlationId,
            reason != null ? "{\"reason\":\"" + reason + "\"}" : null
        );
    }
}
