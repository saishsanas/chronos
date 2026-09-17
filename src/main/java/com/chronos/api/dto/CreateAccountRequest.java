package com.chronos.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record CreateAccountRequest(
    @NotBlank(message = "Currency code must not be blank")
    String currency,

    @Min(value = 0, message = "Initial overdraft limit cannot be negative")
    long initialOverdraftLimitMinor,

    @Positive(message = "Initial transaction limit must be positive")
    long initialTransactionLimitMinor
) {}
