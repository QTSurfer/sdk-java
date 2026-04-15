package net.qtsurfer.api.sdk.errors;

/** Thrown when a stage exceeds its configured timeout. */
public class QTSTimeoutError extends QTSError {
    public QTSTimeoutError(String message) { super(message); }
    public QTSTimeoutError(String message, Throwable cause) { super(message, cause); }
}
