package com.chronos.api.dto;

import java.time.Instant;

public record ApiErrorResponse(
    Instant timestamp,
    int status,
    String errorCode,
    String message,
    String path,
    String correlationId
) {
    public static ApiErrorResponse of(int status, String errorCode, String message, String path, String correlationId) {
        return new ApiErrorResponse(
            Instant.now(),
            status,
            errorCode,
            message,
            path,
            correlationId
        );
    }
}
