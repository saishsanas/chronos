package com.chronos.api.dto;

import com.chronos.domain.account.CorrectionDirection;
import com.chronos.domain.account.CorrectionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

public record IssueCorrectionRequest(
    @NotNull(message = "Target event ID must not be null")
    UUID targetEventId,

    @NotNull(message = "Correction type must not be null")
    CorrectionType correctionType,

    @NotNull(message = "Correction direction must not be null")
    CorrectionDirection direction,

    @Positive(message = "Adjustment amount must be positive")
    long adjustmentAmountMinor,

    @NotBlank(message = "Correction reason must not be blank")
    String reason
) {}
