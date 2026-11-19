package com.qtsurfer.api.sdk.errors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.qtsurfer.api.client.invoker.ApiClient;
import com.qtsurfer.api.client.invoker.ApiException;
import com.qtsurfer.api.client.model.AuthTokenError;
import com.qtsurfer.api.client.model.AuthTokenRejected;

import java.util.Optional;

/**
 * Thrown when the {@code authenticate()} helper cannot mint a JWT — typically because
 * no API key was supplied (neither argument nor {@code QTSURFER_APIKEY}
 * environment variable), or the API key exchange returned 401 / a non-success
 * status from {@code POST /v1/auth/token}.
 */
public class QTSAuthError extends QTSError {

    private final AuthTokenError authTokenError;
    private final AuthTokenRejected authTokenRejected;

    public QTSAuthError(String message) {
        super(message);
        authTokenError = null;
        authTokenRejected = null;
    }

    public QTSAuthError(String message, Throwable cause) {
        super(message, cause);
        authTokenError = parseAuthTokenError(cause);
        authTokenRejected = parseAuthTokenRejected(cause);
    }

    /** @return the typed {@code 403} token-exchange error, when supplied by the API */
    public Optional<AuthTokenError> getAuthTokenError() {
        return Optional.ofNullable(authTokenError);
    }

    /**
     * @return the typed {@code 401} rejection for a missing, unknown, revoked, or expired API key
     */
    public Optional<AuthTokenRejected> getAuthTokenRejected() {
        return Optional.ofNullable(authTokenRejected);
    }

    private static AuthTokenError parseAuthTokenError(Throwable cause) {
        for (Throwable current = cause; current != null; current = current.getCause()) {
            if (current instanceof ApiException exception && exception.getCode() == 403
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

    private static AuthTokenRejected parseAuthTokenRejected(Throwable cause) {
        for (Throwable current = cause; current != null; current = current.getCause()) {
            if (current instanceof ApiException exception && exception.getCode() == 401
                    && exception.getResponseBody() != null && !exception.getResponseBody().isBlank()) {
                try {
                    return new ApiClient().getObjectMapper()
                            .readValue(exception.getResponseBody(), AuthTokenRejected.class);
                } catch (JsonProcessingException ignored) {
                    return null;
                }
            }
        }
        return null;
    }
}
