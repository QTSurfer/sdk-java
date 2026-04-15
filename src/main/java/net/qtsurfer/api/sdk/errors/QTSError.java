package net.qtsurfer.api.sdk.errors;

/**
 * Root of the QTSurfer SDK exception hierarchy. All SDK errors are unchecked;
 * consumers can catch {@code QTSError} to handle any SDK-specific failure, or
 * match on subclasses to react to a specific stage.
 */
public class QTSError extends RuntimeException {

    public QTSError(String message) {
        super(message);
    }

    public QTSError(String message, Throwable cause) {
        super(message, cause);
    }
}
