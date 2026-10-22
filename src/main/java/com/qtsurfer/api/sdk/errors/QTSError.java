package com.qtsurfer.api.sdk.errors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.qtsurfer.api.client.invoker.ApiClient;
import com.qtsurfer.api.client.invoker.ApiException;
import com.qtsurfer.api.client.model.ErrorNextCall;
import com.qtsurfer.api.client.model.InsufficientScopeError;
import com.qtsurfer.api.client.model.ResponseError;

import java.net.http.HttpHeaders;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Root of the QTSurfer SDK exception hierarchy. All SDK errors are unchecked;
 * consumers can catch {@code QTSError} to handle any SDK-specific failure, or
 * match on subclasses to react to a specific stage.
 */
public class QTSError extends RuntimeException {

    private final ApiException apiException;
    private final ResponseError responseError;
    private final InsufficientScopeError insufficientScopeError;

    public QTSError(String message) {
        this(message, null);
    }

    public QTSError(String message, Throwable cause) {
        super(message, cause);
        apiException = findApiException(cause);
        responseError = parseResponseError(apiException);
        insufficientScopeError = parseInsufficientScopeError(apiException);
    }

    /** @return the HTTP status when this error originated in an API response */
    public OptionalInt getStatusCode() {
        return apiException == null || apiException.getCode() <= 0
                ? OptionalInt.empty() : OptionalInt.of(apiException.getCode());
    }

    /** @return the structured server error when the response body has that shape */
    public Optional<ResponseError> getResponseError() { return Optional.ofNullable(responseError); }

    /** @return the typed 403 error when an operation is missing a required API-key scope */
    public Optional<InsufficientScopeError> getInsufficientScopeError() {
        return Optional.ofNullable(insufficientScopeError);
    }

    /** @return the missing resource/action grant, when this is an insufficient-scope error */
    public Optional<String> getRequiredScope() {
        return getInsufficientScopeError().map(InsufficientScopeError::getRequired);
    }

    @Override
    public String getMessage() {
        String message = super.getMessage();
        return getRequiredScope().map(required -> message + " (required scope: " + required + ")")
                .orElse(message);
    }

    /** @return whether the API marks the same request as retryable */
    public Optional<Boolean> getRetryable() {
        return getResponseError().map(ResponseError::getRetryable);
    }

    /** @return a stable cause token; handle unknown tokens like an absent reason */
    public Optional<String> getReason() {
        return getResponseError().map(ResponseError::getReason);
    }

    /** @return a suggested recovery request; the SDK never executes it automatically */
    public Optional<ErrorNextCall> getNext() {
        return getResponseError().map(ResponseError::getNext);
    }

    /** @return response headers, including {@code Retry-After}, when available */
    public Optional<HttpHeaders> getResponseHeaders() {
        return apiException == null ? Optional.empty() : Optional.ofNullable(apiException.getResponseHeaders());
    }

    private static ApiException findApiException(Throwable cause) {
        for (Throwable current = cause; current != null; current = current.getCause()) {
            if (current instanceof ApiException exception) return exception;
        }
        return null;
    }

    private static ResponseError parseResponseError(ApiException exception) {
        if (exception == null || exception.getResponseBody() == null || exception.getResponseBody().isBlank()) {
            return null;
        }
        try {
            return new ApiClient().getObjectMapper().readValue(exception.getResponseBody(), ResponseError.class);
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }

    private static InsufficientScopeError parseInsufficientScopeError(ApiException exception) {
        if (exception == null || exception.getResponseBody() == null || exception.getResponseBody().isBlank()) {
            return null;
        }
        try {
            return new ApiClient().getObjectMapper()
                    .readValue(exception.getResponseBody(), InsufficientScopeError.class);
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }
}
