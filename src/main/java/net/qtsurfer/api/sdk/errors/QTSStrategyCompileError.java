package net.qtsurfer.api.sdk.errors;

/** Thrown when a strategy fails to compile server-side. */
public class QTSStrategyCompileError extends QTSError {
    public QTSStrategyCompileError(String message) { super(message); }
    public QTSStrategyCompileError(String message, Throwable cause) { super(message, cause); }
}
