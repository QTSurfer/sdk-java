package com.qtsurfer.api.sdk.internal;

import dev.failsafe.FailsafeExecutor;
import dev.failsafe.TimeoutExceededException;
import com.qtsurfer.api.sdk.errors.QTSCanceledError;
import com.qtsurfer.api.sdk.errors.QTSError;
import com.qtsurfer.api.sdk.errors.QTSTimeoutError;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The single polling loop every workflow stage runs on, on top of
 * {@link Policies#stagePoller}: interrupt handling, the per-attempt callback,
 * and the mapping from Failsafe's failures onto the SDK error hierarchy.
 *
 * <p>Deliberately knows nothing about the generated models — a stage supplies
 * its own fetch, its own "keep going" predicate and its own per-attempt
 * callback, so adding a stage never widens this class.
 */
public final class Polling {

    private Polling() {}

    /**
     * Poll {@code fetch} until {@code retryWhile} stops matching, then return
     * the last result.
     *
     * @param stage           label used in the timeout message, e.g. {@code PREPARING}
     * @param pollInterval    initial interval between attempts
     * @param maxPollInterval upper bound of the exponential backoff
     * @param timeout         overall deadline for this stage; {@code null} disables
     * @param fetch           reads the current state
     * @param retryWhile      {@code true} while the state is not terminal
     * @param onResult        called with every attempt's result, terminal or not; may be {@code null}
     * @param <T>             polled state type
     * @return the first result {@code retryWhile} rejected
     * @throws QTSTimeoutError  when the stage outlives {@code timeout}
     * @throws QTSCanceledError when the polling thread is interrupted
     * @throws QTSError         when {@code fetch} fails; the original error is rethrown unchanged
     *                          if it already is a {@code QTSError}
     */
    public static <T> T poll(
            String stage,
            Duration pollInterval,
            Duration maxPollInterval,
            Duration timeout,
            Supplier<T> fetch,
            Predicate<T> retryWhile,
            Consumer<T> onResult) {

        FailsafeExecutor<T> failsafe =
                Policies.stagePoller(pollInterval, maxPollInterval, timeout, retryWhile);

        Supplier<T> wrapped = () -> {
            if (Thread.currentThread().isInterrupted()) {
                throw new QTSCanceledError("Workflow aborted");
            }
            T result = fetch.get();
            if (onResult != null) {
                onResult.accept(result);
            }
            return result;
        };

        try {
            return failsafe.get(wrapped::get);
        } catch (TimeoutExceededException ex) {
            throw new QTSTimeoutError("Stage " + stage + " exceeded " + timeout, ex);
        } catch (CancellationException | dev.failsafe.FailsafeException ex) {
            if (Thread.currentThread().isInterrupted()) {
                throw new QTSCanceledError("Workflow aborted", ex);
            }
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            if (cause instanceof QTSError qts) throw qts;
            throw new QTSError("Poll failed: " + cause.getMessage(), cause);
        }
    }

    /**
     * Completion percentage, or {@code null} when the job does not expose
     * enough size information to compute one.
     *
     * @param size      total units of work
     * @param completed units finished so far
     * @return 0-100, or {@code null}
     */
    public static Double percent(Integer size, Integer completed) {
        if (size == null || completed == null || size <= 0) return null;
        return (completed.doubleValue() / size) * 100.0;
    }
}
