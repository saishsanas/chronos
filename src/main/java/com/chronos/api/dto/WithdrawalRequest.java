package com.chronos.api.dto;

import jakarta.validation.constraints.Positive;

public record WithdrawalRequest(
    @Positive(message = "Withdrawal amount must be positive")
    long amountMinor,

    String currency
) {}
