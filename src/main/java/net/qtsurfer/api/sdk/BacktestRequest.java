package net.qtsurfer.api.sdk;

import java.util.Objects;

/**
 * A single-instrument backtest request.
 *
 * @param strategy     strategy source code (Java)
 * @param exchangeId   exchange identifier, e.g. {@code "binance"}
 * @param instrument   instrument symbol, e.g. {@code "BTC/USDT"}
 * @param from         range start (ISO-8601, ISO DATE, or BASIC ISO DATE)
 * @param to           range end (same formats as {@code from}; must be {@code > from})
 * @param storeSignals when {@code Boolean.TRUE}, the worker uploads emitted signals to object storage
 *                     and the result includes {@code signalsUrl} / {@code signalsId}; {@code null} keeps the server default
 */
public record BacktestRequest(
        String strategy,
        String exchangeId,
        String instrument,
        String from,
        String to,
        Boolean storeSignals
) {
    public BacktestRequest {
        Objects.requireNonNull(strategy, "strategy");
        Objects.requireNonNull(exchangeId, "exchangeId");
        Objects.requireNonNull(instrument, "instrument");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String strategy;
        private String exchangeId;
        private String instrument;
        private String from;
        private String to;
        private Boolean storeSignals;

        public Builder strategy(String strategy) { this.strategy = strategy; return this; }
        public Builder exchangeId(String exchangeId) { this.exchangeId = exchangeId; return this; }
        public Builder instrument(String instrument) { this.instrument = instrument; return this; }
        public Builder from(String from) { this.from = from; return this; }
        public Builder to(String to) { this.to = to; return this; }
        public Builder storeSignals(boolean storeSignals) { this.storeSignals = storeSignals; return this; }

        public BacktestRequest build() {
            return new BacktestRequest(strategy, exchangeId, instrument, from, to, storeSignals);
        }
    }
}
