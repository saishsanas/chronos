package com.chronos.api.dto;

import jakarta.validation.constraints.NotBlank;

public record FreezeAccountRequest(
    @NotBlank(message = "Freeze reason must not be blank")
    String reason
) {}
