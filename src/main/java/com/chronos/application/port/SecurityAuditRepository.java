package com.chronos.application.port;

import com.chronos.domain.security.audit.SecurityAuditRecord;

import java.util.List;

public interface SecurityAuditRepository {

    void append(SecurityAuditRecord record);

    List<SecurityAuditRecord> findPaged(int limit, int offset, String action, String actorUsername);

    long count();
}
