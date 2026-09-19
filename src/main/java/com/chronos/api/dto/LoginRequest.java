package com.chronos.api.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
    @NotBlank(message = "username must not be blank")
    String username,

    @NotBlank(message = "password must not be blank")
    String password
) {}
