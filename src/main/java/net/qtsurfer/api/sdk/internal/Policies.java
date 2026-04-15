package net.qtsurfer.api.sdk.internal;

import dev.failsafe.Failsafe;
import dev.failsafe.FailsafeExecutor;
import dev.failsafe.RetryPolicy;
import dev.failsafe.Timeout;
import net.qtsurfer.api.sdk.internal.StatusNormalizer.Normalized;

import java.time.Duration;
import java.util.function.Predicate;

/** Factory helpers that build Failsafe policies shared by every workflow stage. */
public final class Policies {

    private Policies() {}

    /**
     * Build a FailsafeExecutor that polls {@code fn} with exponential backoff
     * while the result matches {@code retryWhile} (expected to be an
     * {@link Normalized#IN_PROGRESS} check). Exceptions are never retried —
     * they propagate so the caller can map them to the appropriate
     * {@code QTSError} subclass.
     */
    public static <T> FailsafeExecutor<T> stagePoller(
            Duration pollInterval,
            Duration maxPollInterval,
            Duration stageTimeout,
            Predicate<T> retryWhile) {

        RetryPolicy<T> retry = RetryPolicy.<T>builder()
                // Retry only based on the result predicate; any throwable aborts.
                .handleResultIf(retryWhile::test)
                .abortOn(Throwable.class)
                .withBackoff(pollInterval, maxPollInterval)
                .withMaxAttempts(-1)
                .build();

        if (stageTimeout == null) {
            return Failsafe.with(retry);
        }
        Timeout<T> timeout = Timeout.<T>builder(stageTimeout)
                .withInterrupt()
                .build();
        return Failsafe.with(timeout, retry);
    }
}
