package com.qtsurfer.api.sdk.errors;

/**
 * Thrown when the {@code auth()} helper cannot mint a JWT — typically because
 * no API key was supplied (neither argument nor {@code QTSURFER_APIKEY}
 * environment variable), or the API key exchange returned 401 / a non-success
 * status from {@code POST /v1/auth/token}.
 */
public class QTSAuthError extends QTSError {

    public QTSAuthError(String message) {
        super(message);
    }

    public QTSAuthError(String message, Throwable cause) {
        super(message, cause);
    }
}
