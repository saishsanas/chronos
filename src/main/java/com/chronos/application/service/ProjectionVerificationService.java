package com.chronos.application.service;

import com.chronos.application.model.TemporalResult;
import com.chronos.application.port.AccountSummaryProjectionRepository;
import com.chronos.domain.projection.AccountSummaryProjection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class ProjectionVerificationService {

    private static final Logger log = LoggerFactory.getLogger(ProjectionVerificationService.class);

    private final AccountSummaryProjectionRepository projectionRepository;
    private final TemporalStateReconstructor temporalReconstructor;

    public record VerificationReport(
        int totalProjectionsChecked,
        int consistentCount,
        int mismatchCount,
        List<String> mismatchDetails,
        boolean isConsistent
    ) {}

    public ProjectionVerificationService(
        AccountSummaryProjectionRepository projectionRepository,
        TemporalStateReconstructor temporalReconstructor
    ) {
        this.projectionRepository = Objects.requireNonNull(projectionRepository, "projectionRepository must not be null");
        this.temporalReconstructor = Objects.requireNonNull(temporalReconstructor, "temporalReconstructor must not be null");
    }

    public VerificationReport verifyAll() {
        List<AccountSummaryProjection> projections = projectionRepository.findAll();
        List<String> mismatches = new ArrayList<>();
        int consistent = 0;

        for (AccountSummaryProjection proj : projections) {
            TemporalResult canonical = temporalReconstructor.reconstructCurrentState(proj.accountId());
            List<String> diffs = compare(proj, canonical);
            if (diffs.isEmpty()) {
                consistent++;
            } else {
                mismatches.add("Account " + proj.accountId() + " diffs: " + String.join(", ", diffs));
            }
        }

        boolean isConsistent = mismatches.isEmpty();
        log.info("Projection verification complete: {} total checked, {} consistent, {} mismatches",
            projections.size(), consistent, mismatches.size());

        return new VerificationReport(projections.size(), consistent, mismatches.size(), Collections.unmodifiableList(mismatches), isConsistent);
    }

    public List<String> compare(AccountSummaryProjection projection, TemporalResult canonicalResult) {
        List<String> diffs = new ArrayList<>();
        var state = canonicalResult.reconstructedState();

        if (projection.sequenceNumber() != state.sequenceNumber()) {
            diffs.add("sequenceNumber mismatch (projection=" + projection.sequenceNumber() + ", canonical=" + state.sequenceNumber() + ")");
        }
        if (projection.balanceMinor() != state.balanceMinor()) {
            diffs.add("balanceMinor mismatch (projection=" + projection.balanceMinor() + ", canonical=" + state.balanceMinor() + ")");
        }
        if (projection.status() != state.status()) {
            diffs.add("status mismatch (projection=" + projection.status() + ", canonical=" + state.status() + ")");
        }
        if (projection.overdraftLimitMinor() != state.overdraftLimitMinor()) {
            diffs.add("overdraftLimitMinor mismatch (projection=" + projection.overdraftLimitMinor() + ", canonical=" + state.overdraftLimitMinor() + ")");
        }
        if (projection.transactionLimitMinor() != state.transactionLimitMinor()) {
            diffs.add("transactionLimitMinor mismatch (projection=" + projection.transactionLimitMinor() + ", canonical=" + state.transactionLimitMinor() + ")");
        }

        return diffs;
    }
}
