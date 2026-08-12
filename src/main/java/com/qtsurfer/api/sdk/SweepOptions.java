package com.qtsurfer.api.sdk;

import java.time.Duration;
import java.util.function.Consumer;

/**
 * Tuning knobs for a {@link com.qtsurfer.api.sdk.QTSurfer#sweep sweep}
 * invocation.
 *
 * @param onProgress      callback fired on stage transitions and after each poll (nullable)
 * @param pollInterval    initial interval between status polls; the SDK backs off exponentially
 *                        up to {@code maxPollInterval}
 * @param maxPollInterval upper bound of the exponential backoff
 * @param timeout         per-stage timeout; {@code null} disables. A sweep is many backtests, so
 *                        the execute stage legitimately outlasts anything a single run would take
 * @param ranking         how to order the leaderboard the poll reads. {@code null} sends no
 *                        preference and takes the platform default, which is
 *                        {@link SweepRanking#PLATEAU} — so leaving this unset does <em>not</em>
 *                        give you raw objective order. What was actually applied is reported on
 *                        the result; see {@link SweepRanking}. <strong>Ignored entirely when
 *                        {@code order} is {@link SweepOrder#NATURAL}</strong>, which is always
 *                        ordered by {@code runIx}
 * @param order           which view of the rows to read. {@code null} takes the platform default,
 *                        {@link SweepOrder#RANKED} — the sorted, display-capped leaderboard.
 *                        {@link SweepOrder#NATURAL} returns every available row untruncated, and
 *                        is the only way to reach rows the ranked view dropped when the result
 *                        reports {@code truncated}
 */
public record SweepOptions(
        Consumer<SweepProgressEvent> onProgress,
        Duration pollInterval,
        Duration maxPollInterval,
        Duration timeout,
        SweepRanking ranking,
        SweepOrder order
) {
    /** Initial interval between polls when none is configured. */
    public static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(2);
    /** Upper bound of the exponential backoff when none is configured. */
    public static final Duration DEFAULT_MAX_POLL_INTERVAL = Duration.ofSeconds(15);

    /**
     * Defaults: no progress callback, no stage timeout, and whatever ordering
     * the platform defaults to.
     *
     * <p>The poll intervals are longer than a single backtest's, because a
     * sweep is many backtests and its leaderboard changes on the timescale of
     * shards finishing rather than ticks.
     *
     * @return the default options
     */
    public static SweepOptions defaults() {
        return new SweepOptions(
                null, DEFAULT_POLL_INTERVAL, DEFAULT_MAX_POLL_INTERVAL, null, null, null);
    }

    /**
     * Start building options.
     *
     * @return a fresh builder
     */
    public static Builder builder() { return new Builder(); }

    /** Fills in the default poll intervals when either is left {@code null}. */
    public SweepOptions {
        if (pollInterval == null) pollInterval = DEFAULT_POLL_INTERVAL;
        if (maxPollInterval == null) maxPollInterval = DEFAULT_MAX_POLL_INTERVAL;
    }

    /** Fluent builder for {@link SweepOptions}. */
    public static final class Builder {
        private Consumer<SweepProgressEvent> onProgress;
        private Duration pollInterval = DEFAULT_POLL_INTERVAL;
        private Duration maxPollInterval = DEFAULT_MAX_POLL_INTERVAL;
        private Duration timeout;
        private SweepRanking ranking;
        private SweepOrder order;

        /**
         * @param onProgress callback for stage transitions and poll updates
         * @return this builder
         */
        public Builder onProgress(Consumer<SweepProgressEvent> onProgress) { this.onProgress = onProgress; return this; }

        /**
         * @param pollInterval initial interval between status polls
         * @return this builder
         */
        public Builder pollInterval(Duration pollInterval) { this.pollInterval = pollInterval; return this; }

        /**
         * @param maxPollInterval upper bound of the exponential backoff
         * @return this builder
         */
        public Builder maxPollInterval(Duration maxPollInterval) { this.maxPollInterval = maxPollInterval; return this; }

        /**
         * @param timeout per-stage timeout; {@code null} disables
         * @return this builder
         */
        public Builder timeout(Duration timeout) { this.timeout = timeout; return this; }

        /**
         * How to order the leaderboard; {@code null} takes the platform
         * default. Has no effect at all alongside
         * {@link SweepOrder#NATURAL}.
         *
         * @param ranking plateau or raw ordering of the ranked view
         * @return this builder
         */
        public Builder ranking(SweepRanking ranking) { this.ranking = ranking; return this; }

        /**
         * Which view of the rows to read; {@code null} takes the platform
         * default ({@link SweepOrder#RANKED}). Choose
         * {@link SweepOrder#NATURAL} to receive every row untruncated — the
         * only route to rows the ranked view dropped.
         *
         * @param order the ranked display view or the natural materialisation order
         * @return this builder
         */
        public Builder order(SweepOrder order) { this.order = order; return this; }

        /**
         * Build the options.
         *
         * @return the immutable options
         */
        public SweepOptions build() {
            return new SweepOptions(
                    onProgress, pollInterval, maxPollInterval, timeout, ranking, order);
        }
    }
}
