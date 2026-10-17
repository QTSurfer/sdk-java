package com.qtsurfer.api.sdk.errors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.qtsurfer.api.client.invoker.ApiClient;
import com.qtsurfer.api.client.invoker.ApiException;
import com.qtsurfer.api.client.model.AuthTokenError;

import java.util.Optional;

/**
 * Thrown when the {@code authenticate()} helper cannot mint a JWT — typically because
 * no API key was supplied (neither argument nor {@code QTSURFER_APIKEY}
 * environment variable), or the API key exchange returned 401 / a non-success
 * status from {@code POST /v1/auth/token}.
 */
public class QTSAuthError extends QTSError {

    private final AuthTokenError authTokenError;

    public QTSAuthError(String message) {
        super(message);
        authTokenError = null;
    }

    public QTSAuthError(String message, Throwable cause) {
        super(message, cause);
        authTokenError = parseAuthTokenError(cause);
    }

    /** @return the typed {@code 401}/{@code 403} token-exchange error, when supplied by the API */
    public Optional<AuthTokenError> getAuthTokenError() {
        return Optional.ofNullable(authTokenError);
    }

    private static AuthTokenError parseAuthTokenError(Throwable cause) {
        for (Throwable current = cause; current != null; current = current.getCause()) {
            if (current instanceof ApiException exception
                    && exception.getResponseBody() != null && !exception.getResponseBody().isBlank()) {
                try {
                    return new ApiClient().getObjectMapper()
                            .readValue(exception.getResponseBody(), AuthTokenError.class);
                } catch (JsonProcessingException ignored) {
                    return null;
                }
            }
        }
        return null;
    }
}
