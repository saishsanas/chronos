package com.chronos.api.dto;

import jakarta.validation.constraints.NotBlank;

public record UnfreezeAccountRequest(
    @NotBlank(message = "Unfreeze reason must not be blank")
    String reason
) {}
