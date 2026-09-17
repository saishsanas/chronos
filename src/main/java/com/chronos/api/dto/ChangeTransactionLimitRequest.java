package com.chronos.api.dto;

import jakarta.validation.constraints.Min;

public record ChangeTransactionLimitRequest(
    @Min(value = 0, message = "New transaction limit cannot be negative")
    long newTransactionLimitMinor,

    String reason
) {}
