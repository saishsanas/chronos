package com.chronos.api.dto;

import jakarta.validation.constraints.Positive;

public record DepositRequest(
    @Positive(message = "Deposit amount must be positive")
    long amountMinor,

    String currency
) {}
