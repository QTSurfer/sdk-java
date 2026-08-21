package com.qtsurfer.api.sdk;

import com.qtsurfer.api.client.model.ExecuteSweepAccepted;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A parameter sweep over one instrument and one window: the same strategy run
 * once per parameter vector, scored and ranked against a single objective.
 *
 * <pre>{@code
 * SweepRequest request = SweepRequest.builder()
 *     .strategy(source)
 *     .exchangeId("binance")
 *     .instrument("BTC/USDT")
 *     .from("2026-01-01T00:00:00Z")
 *     .to("2026-02-01T00:00:00Z")
 *     .param("rsi.period", ParamAxis.range(7, 28, 1))
 *     .param("useTrendFilter", ParamAxis.of(true, false))
 *     .objective(SweepObjective.SHARPE)
 *     .build();
 * }</pre>
 *
 * @param strategy    strategy source code (Java), compiled once and reused by every trial
 * @param exchangeId  exchange identifier, e.g. {@code "binance"}
 * @param instrument  instrument symbol, e.g. {@code "BTC/USDT"}
 * @param from        range start (ISO-8601, ISO DATE, or BASIC ISO DATE)
 * @param to          range end (same formats as {@code from}; must be {@code > from})
 * @param params      the grid: one {@link ParamAxis} per strategy property to vary. At least one
 * @param sampler     how the grid becomes the list of vectors actually run; {@code null} keeps
 *                    the platform default (the full cross product)
 * @param samples     how many vectors to draw for {@link SweepSampler#RANDOM} and
 *                    {@link SweepSampler#LHS}; ignored by {@link SweepSampler#GRID}
 * @param seed        reproducibility seed. {@code null} lets the platform generate one and report
 *                    it back on {@link ExecuteSweepAccepted#getSeed()}, so a randomly sampled
 *                    sweep can be replayed exactly by submitting the same seed again
 * @param objective   the metric to optimize and rank by; {@code null} keeps the platform default.
 *                    It is also what {@link Sweep#sensitivity()} aggregates unless told otherwise
 * @param walkForward opt into walk-forward validation, which changes both what runs and the shape
 *                    of the answer; {@code null} runs an ordinary sweep. See {@link WalkForwardSpec}
 */
public record SweepRequest(
        String strategy,
        String exchangeId,
        String instrument,
        String from,
        String to,
        Map<String, ParamAxis> params,
        SweepSampler sampler,
        Integer samples,
        Long seed,
        SweepObjective objective,
        WalkForwardSpec walkForward
) {
    /**
     * @throws NullPointerException     when a required field is missing
     * @throws IllegalArgumentException when the grid is empty
     */
    public SweepRequest {
        Objects.requireNonNull(strategy, "strategy");
        Objects.requireNonNull(exchangeId, "exchangeId");
        Objects.requireNonNull(instrument, "instrument");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(params, "params");
        if (params.isEmpty()) {
            throw new IllegalArgumentException("params must hold at least one axis");
        }
        params = Map.copyOf(params);
    }

    /**
     * Start building a request.
     *
     * @return a fresh builder
     */
    public static Builder builder() { return new Builder(); }

    /** Fluent builder for {@link SweepRequest}. */
    public static final class Builder {
        private String strategy;
        private String exchangeId;
        private String instrument;
        private String from;
        private String to;
        private final Map<String, ParamAxis> params = new LinkedHashMap<>();
        private SweepSampler sampler;
        private Integer samples;
        private Long seed;
        private SweepObjective objective;
        private WalkForwardSpec walkForward;

        /**
         * @param strategy strategy source code (Java)
         * @return this builder
         */
        public Builder strategy(String strategy) { this.strategy = strategy; return this; }

        /**
         * @param exchangeId exchange identifier, e.g. {@code "binance"}
         * @return this builder
         */
        public Builder exchangeId(String exchangeId) { this.exchangeId = exchangeId; return this; }

        /**
         * @param instrument instrument symbol, e.g. {@code "BTC/USDT"}
         * @return this builder
         */
        public Builder instrument(String instrument) { this.instrument = instrument; return this; }

        /**
         * @param from range start
         * @return this builder
         */
        public Builder from(String from) { this.from = from; return this; }

        /**
         * @param to range end
         * @return this builder
         */
        public Builder to(String to) { this.to = to; return this; }

        /**
         * Add one axis to the grid. Calling this twice with the same name
         * replaces the earlier axis.
         *
         * <p>The {@code name} must be the strategy property's
         * {@code @StrategyProperty(name = "...")} annotation value (e.g.
         * {@code "rsi.period"}), NOT the Java field name (e.g.
         * {@code rsiPeriod}). Using the field name is silently ignored by the
         * platform and every trial runs with the default.
         *
         * @param name the strategy property to vary (the {@code @StrategyProperty} name)
         * @param axis the values to try for it
         * @return this builder
         */
        public Builder param(String name, ParamAxis axis) {
            this.params.put(
                    Objects.requireNonNull(name, "name"),
                    Objects.requireNonNull(axis, "axis"));
            return this;
        }

        /**
         * Replace the whole grid.
         *
         * @param params one axis per strategy property to vary
         * @return this builder
         */
        public Builder params(Map<String, ParamAxis> params) {
            this.params.clear();
            this.params.putAll(Objects.requireNonNull(params, "params"));
            return this;
        }

        /**
         * @param sampler how the grid becomes the vectors actually run
         * @return this builder
         */
        public Builder sampler(SweepSampler sampler) { this.sampler = sampler; return this; }

        /**
         * @param samples vectors to draw for a random or Latin-hypercube sampler
         * @return this builder
         */
        public Builder samples(int samples) { this.samples = samples; return this; }

        /**
         * @param seed reproducibility seed; omit to let the platform pick and report one
         * @return this builder
         */
        public Builder seed(long seed) { this.seed = seed; return this; }

        /**
         * @param objective the metric to optimize and rank by
         * @return this builder
         */
        public Builder objective(SweepObjective objective) { this.objective = objective; return this; }

        /**
         * @param walkForward opt into walk-forward validation
         * @return this builder
         */
        public Builder walkForward(WalkForwardSpec walkForward) { this.walkForward = walkForward; return this; }

        /**
         * @param folds walk forward over this many folds, at platform-default in-sample share
         * @return this builder
         */
        public Builder walkForward(int folds) { return walkForward(WalkForwardSpec.of(folds)); }

        /**
         * Build the request.
         *
         * @return the immutable request
         */
        public SweepRequest build() {
            return new SweepRequest(
                    strategy, exchangeId, instrument, from, to,
                    params, sampler, samples, seed, objective, walkForward);
        }
    }
}
