package com.qtsurfer.api.sdk.errors;

/**
 * Thrown when the execute stage of a run this SDK is performing fails: the
 * platform rejected the submission, reported the run as failed, or the poll
 * driving it could not reach the platform. Covers both a single backtest and a
 * sweep.
 *
 * <p>Standalone reads do not raise this. They are not performing a run, so
 * there is no execution for a failure to be about — a read that cannot reach
 * the platform raises a plain {@link QTSError}, and a run that <em>ended</em>
 * badly is reported to them as a value rather than as an error at all.
 */
public class QTSExecutionError extends QTSError {
    public QTSExecutionError(String message) { super(message); }
    public QTSExecutionError(String message, Throwable cause) { super(message, cause); }
}
