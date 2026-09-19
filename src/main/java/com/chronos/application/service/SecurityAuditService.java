package com.chronos.application.service;

import com.chronos.application.port.SecurityAuditRepository;
import com.chronos.domain.security.audit.SecurityAuditRecord;
import com.chronos.infrastructure.observability.ChronosMetrics;
import com.chronos.infrastructure.security.SecurityPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class SecurityAuditService {

    private static final Logger log = LoggerFactory.getLogger(SecurityAuditService.class);

    private final SecurityAuditRepository auditRepository;
    private final ChronosMetrics metrics;

    public SecurityAuditService(
        SecurityAuditRepository auditRepository,
        @Autowired(required = false) ChronosMetrics metrics
    ) {
        this.auditRepository = Objects.requireNonNull(auditRepository, "auditRepository must not be null");
        this.metrics = metrics;
    }

    public void recordLoginSuccess(UUID userId, String username, String correlationId) {
        SecurityAuditRecord record = SecurityAuditRecord.loginSuccess(userId, username, correlationId, null);
        safelyAppend(record);
    }

    public void recordLoginFailure(String attemptedUsername, String correlationId, String reason) {
        SecurityAuditRecord record = SecurityAuditRecord.loginFailure(attemptedUsername, correlationId, reason);
        safelyAppend(record);
    }

    public void recordAccountCommand(
        SecurityPrincipal principal,
        String commandType,
        UUID accountId,
        UUID correlationId
    ) {
        UUID actorUserId = principal != null ? principal.userId() : null;
        String actorUsername = principal != null ? principal.username() : "ANONYMOUS";
        String corrStr = correlationId != null ? correlationId.toString() : null;

        SecurityAuditRecord record = SecurityAuditRecord.accountCommand(
            actorUserId,
            actorUsername,
            commandType,
            accountId,
            corrStr,
            "SUCCESS",
            null
        );
        safelyAppend(record);
    }

    public void recordProjectionRebuild(
        SecurityPrincipal principal,
        String jobId,
        String scope,
        String correlationId
    ) {
        UUID actorUserId = principal != null ? principal.userId() : null;
        String actorUsername = principal != null ? principal.username() : "ANONYMOUS";

        SecurityAuditRecord record = SecurityAuditRecord.projectionRebuild(
            actorUserId,
            actorUsername,
            scope,
            jobId,
            correlationId,
            "SUCCESS"
        );
        safelyAppend(record);
    }

    public void recordSecurityDenied(
        SecurityPrincipal principal,
        String resourceType,
        String resourceId,
        String correlationId,
        String reason
    ) {
        UUID actorUserId = principal != null ? principal.userId() : null;
        String actorUsername = principal != null ? principal.username() : "ANONYMOUS";

        SecurityAuditRecord record = SecurityAuditRecord.securityDenied(
            actorUserId,
            actorUsername,
            resourceType,
            resourceId,
            correlationId,
            reason
        );
        safelyAppend(record);
    }

    public List<SecurityAuditRecord> getAuditLog(int limit, int offset, String action, String actorUsername) {
        return auditRepository.findPaged(limit, offset, action, actorUsername);
    }

    public long getTotalAuditCount() {
        return auditRepository.count();
    }

    private void safelyAppend(SecurityAuditRecord record) {
        try {
            auditRepository.append(record);
            log.info("Security audit recorded: action={}, actor={}, resource={}:{}, outcome={}",
                record.action(), record.actorUsername(), record.resourceType(), record.resourceId(), record.outcome());
        } catch (Exception e) {
            log.error("Failed to persist security audit record: action={}, actor={}, correlationId={}, error={}",
                record.action(), record.actorUsername(), record.correlationId(), e.getMessage(), e);
            if (metrics != null) {
                metrics.recordSecurityAuditWriteFailure();
            }
            // Deliberately do not rethrow: audit failure must not abort authoritative domain state
        }
    }
}
