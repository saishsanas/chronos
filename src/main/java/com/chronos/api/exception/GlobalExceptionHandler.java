package com.chronos.api.exception;

import com.chronos.api.dto.ApiErrorResponse;
import com.chronos.application.port.ConsumerEventIntegrityException;
import com.chronos.application.port.InvalidEventEnvelopeException;
import com.chronos.application.port.OptimisticConcurrencyException;
import com.chronos.domain.account.exception.*;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Objects;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private String getCorrelationId(HttpServletRequest request) {
        String corrHeader = request.getHeader("X-Correlation-Id");
        return corrHeader != null && !corrHeader.isBlank() ? corrHeader : "N/A";
    }

    // 400 Bad Request
    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        IllegalArgumentException.class,
        InvalidEventEnvelopeException.class,
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ApiErrorResponse> handleBadRequest(Exception ex, HttpServletRequest request) {
        String message = ex.getMessage();
        if (ex instanceof MethodArgumentNotValidException valEx) {
            message = valEx.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .collect(Collectors.joining("; "));
        } else if (ex instanceof HttpMessageNotReadableException) {
            message = "Malformed JSON request body";
        }

        log.warn("Bad Request [{}]: {}", request.getRequestURI(), message);
        ApiErrorResponse body = ApiErrorResponse.of(
            HttpStatus.BAD_REQUEST.value(),
            "BAD_REQUEST",
            message,
            request.getRequestURI(),
            getCorrelationId(request)
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    // 404 Not Found
    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(AccountNotFoundException ex, HttpServletRequest request) {
        log.warn("Not Found [{}]: {}", request.getRequestURI(), ex.getMessage());
        ApiErrorResponse body = ApiErrorResponse.of(
            HttpStatus.NOT_FOUND.value(),
            "ACCOUNT_NOT_FOUND",
            ex.getMessage(),
            request.getRequestURI(),
            getCorrelationId(request)
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    // 409 Conflict (Optimistic Concurrency Control)
    @ExceptionHandler(OptimisticConcurrencyException.class)
    public ResponseEntity<ApiErrorResponse> handleConflict(OptimisticConcurrencyException ex, HttpServletRequest request) {
        log.warn("Concurrency Conflict [{}]: {}", request.getRequestURI(), ex.getMessage());
        ApiErrorResponse body = ApiErrorResponse.of(
            HttpStatus.CONFLICT.value(),
            "OPTIMISTIC_CONCURRENCY_CONFLICT",
            ex.getMessage(),
            request.getRequestURI(),
            getCorrelationId(request)
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    // 422 Unprocessable Entity (Domain / Business Rule Violations)
    @ExceptionHandler({
        InvalidStateTransitionException.class,
        DomainValidationException.class,
        InsufficientFundsException.class,
        TransactionLimitExceededException.class,
        InvalidCorrectionException.class,
        IllegalStateException.class,
        ConsumerEventIntegrityException.class
    })
    public ResponseEntity<ApiErrorResponse> handleUnprocessableEntity(Exception ex, HttpServletRequest request) {
        log.warn("Unprocessable Entity [{}]: {}", request.getRequestURI(), ex.getMessage());
        String errorCode = ex.getClass().getSimpleName().replaceAll("Exception$", "").replaceAll("(.)(\\p{Upper})", "$1_$2").toUpperCase();
        ApiErrorResponse body = ApiErrorResponse.of(
            HttpStatus.UNPROCESSABLE_ENTITY.value(),
            errorCode,
            ex.getMessage(),
            request.getRequestURI(),
            getCorrelationId(request)
        );
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    // 500 Internal Server Error
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unexpected Internal Error [{}]: ", request.getRequestURI(), ex);
        ApiErrorResponse body = ApiErrorResponse.of(
            HttpStatus.INTERNAL_SERVER_ERROR.value(),
            "INTERNAL_SERVER_ERROR",
            "An unexpected internal error occurred",
            request.getRequestURI(),
            getCorrelationId(request)
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
