package net.qtsurfer.api.sdk.errors;

/** Thrown when the backtest execution stage fails server-side. */
public class QTSExecutionError extends QTSError {
    public QTSExecutionError(String message) { super(message); }
    public QTSExecutionError(String message, Throwable cause) { super(message, cause); }
}
