package net.qtsurfer.api.sdk;

import java.time.Duration;
import java.util.function.Consumer;

/**
 * Tuning knobs for a {@link net.qtsurfer.api.sdk.QTSurfer#backtest backtest} invocation.
 *
 * @param onProgress      callback fired on stage transitions and after each poll with size info (nullable)
 * @param pollInterval    initial interval between status polls; the SDK backs off exponentially up to {@code maxPollInterval}
 * @param maxPollInterval upper bound of the exponential backoff
 * @param timeout         per-stage timeout; {@code null} disables
 */
public record BacktestOptions(
        Consumer<BacktestProgress> onProgress,
        Duration pollInterval,
        Duration maxPollInterval,
        Duration timeout
) {
    public static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(500);
    public static final Duration DEFAULT_MAX_POLL_INTERVAL = Duration.ofSeconds(5);

    public static BacktestOptions defaults() {
        return new BacktestOptions(null, DEFAULT_POLL_INTERVAL, DEFAULT_MAX_POLL_INTERVAL, null);
    }

    public static Builder builder() { return new Builder(); }

    public BacktestOptions {
        if (pollInterval == null) pollInterval = DEFAULT_POLL_INTERVAL;
        if (maxPollInterval == null) maxPollInterval = DEFAULT_MAX_POLL_INTERVAL;
    }

    public static final class Builder {
        private Consumer<BacktestProgress> onProgress;
        private Duration pollInterval = DEFAULT_POLL_INTERVAL;
        private Duration maxPollInterval = DEFAULT_MAX_POLL_INTERVAL;
        private Duration timeout;

        public Builder onProgress(Consumer<BacktestProgress> onProgress) { this.onProgress = onProgress; return this; }
        public Builder pollInterval(Duration pollInterval) { this.pollInterval = pollInterval; return this; }
        public Builder maxPollInterval(Duration maxPollInterval) { this.maxPollInterval = maxPollInterval; return this; }
        public Builder timeout(Duration timeout) { this.timeout = timeout; return this; }

        public BacktestOptions build() {
            return new BacktestOptions(onProgress, pollInterval, maxPollInterval, timeout);
        }
    }
}
