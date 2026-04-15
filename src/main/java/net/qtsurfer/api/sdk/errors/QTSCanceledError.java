package net.qtsurfer.api.sdk.errors;

/** Thrown when the workflow is canceled by the caller (e.g. {@code CompletableFuture#cancel}). */
public class QTSCanceledError extends QTSError {
    public QTSCanceledError(String message) { super(message); }
    public QTSCanceledError(String message, Throwable cause) { super(message, cause); }
}
