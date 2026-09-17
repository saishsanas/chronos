package com.chronos.api.dto;

import jakarta.validation.constraints.NotBlank;

public record CloseAccountRequest(
    @NotBlank(message = "Close reason must not be blank")
    String reason
) {}
