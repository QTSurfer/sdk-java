package net.qtsurfer.api.sdk.errors;

/** Thrown when the data preparation stage fails (e.g. missing data, rate limit). */
public class QTSPreparationError extends QTSError {
    public QTSPreparationError(String message) { super(message); }
    public QTSPreparationError(String message, Throwable cause) { super(message, cause); }
}
