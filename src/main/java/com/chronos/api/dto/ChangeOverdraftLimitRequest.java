package com.chronos.api.dto;

import jakarta.validation.constraints.Min;

public record ChangeOverdraftLimitRequest(
    @Min(value = 0, message = "New overdraft limit cannot be negative")
    long newOverdraftLimitMinor,

    String reason
) {}
