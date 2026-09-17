package com.chronos.domain.account.command;

import com.chronos.domain.account.CorrectionDirection;
import com.chronos.domain.account.CorrectionType;
import java.util.UUID;

public record IssueCorrection(
    UUID accountId,
    UUID targetEventId,
    CorrectionType correctionType,
    CorrectionDirection direction,
    long adjustmentAmountMinor,
    String reason
) implements AccountCommand {}
