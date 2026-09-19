package com.chronos.api.dto;

import java.util.List;
import java.util.UUID;

public record LoginResponse(
    String accessToken,
    String tokenType,
    long expiresIn,
    UUID userId,
    String username,
    List<String> roles
) {
    public static LoginResponse bearer(String accessToken, long expiresIn, UUID userId, String username, List<String> roles) {
        return new LoginResponse(accessToken, "Bearer", expiresIn, userId, username, roles);
    }
}
