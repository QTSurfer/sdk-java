package com.qtsurfer.api.sdk;

import com.qtsurfer.api.client.model.EquityCurveOptions;
import java.util.Objects;

/**
 * A single-instrument backtest request.
 *
 * <p>Two mutually exclusive data sources. Against a managed exchange, set
 * {@code instrument} and leave {@code datasetId} unset:
 *
 * <pre>{@code
 * BacktestRequest request = BacktestRequest.builder()
 *     .strategy(source)
 *     .exchangeId("binance")
 *     .instrument("BTC/USDT")
 *     .from("2026-01-01T00:00:00Z")
 *     .to("2026-02-01T00:00:00Z")
 *     .build();
 * }</pre>
 *
 * <p>Against a caller-uploaded dataset, set {@code datasetId} instead (and use
 * the reserved {@code exchangeId} value {@code "user"}); {@code instrument} is
 * left unset since it comes from the dataset itself. {@code datasetVersionId}
 * is optional and pins a specific past version instead of the dataset's
 * current one:
 *
 * <pre>{@code
 * BacktestRequest request = BacktestRequest.builder()
 *     .strategy(source)
 *     .exchangeId("user")
 *     .datasetId("ds_3f9a1c2e7b0d4a5f")
 *     .from("2026-01-01T00:00:00Z")
 *     .to("2026-02-01T00:00:00Z")
 *     .build();
 * }</pre>
 *
 * @param strategy         strategy source code (Java)
 * @param exchangeId       exchange identifier, e.g. {@code "binance"}, or the reserved value
 *                         {@code "user"} for a dataset-backed request
 * @param instrument       instrument symbol, e.g. {@code "BTC/USDT"}; mutually exclusive with
 *                         {@code datasetId} — exactly one of the two must be set
 * @param from             range start (ISO-8601, ISO DATE, or BASIC ISO DATE)
 * @param to               range end (same formats as {@code from}; must be {@code > from})
 * @param storeSignals     when {@code Boolean.TRUE}, the worker uploads emitted signals to object storage
 *                         and the result includes {@code signalsUrl} / {@code signalsId}; {@code null} keeps the server default
 * @param datasetId        id of a dataset created with {@link QTSurfer#createDataset}; mutually
 *                         exclusive with {@code instrument}
 * @param datasetVersionId pins a specific past version of {@code datasetId} instead of its current
 *                         one; only valid alongside a non-null {@code datasetId}
 * @param equityCurve      requested server-side transform for the inline result curve; {@code null}
 *                         keeps the platform defaults
 */
public record BacktestRequest(
        String strategy,
        String exchangeId,
        String instrument,
        String from,
        String to,
        Boolean storeSignals,
        String datasetId,
        String datasetVersionId,
        EquityCurveOptions equityCurve
) {
    public BacktestRequest(String strategy, String exchangeId, String instrument, String from, String to,
                           Boolean storeSignals, String datasetId, String datasetVersionId) {
        this(strategy, exchangeId, instrument, from, to, storeSignals, datasetId, datasetVersionId, null);
    }
    public BacktestRequest {
        Objects.requireNonNull(strategy, "strategy");
        Objects.requireNonNull(exchangeId, "exchangeId");
        if (instrument == null && datasetId == null) {
            Objects.requireNonNull(instrument, "instrument");
        }
        if (instrument != null && datasetId != null) {
            throw new IllegalArgumentException("instrument and datasetId are mutually exclusive");
        }
        if (datasetVersionId != null && datasetId == null) {
            throw new IllegalArgumentException("datasetVersionId requires datasetId");
        }
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
        private String datasetId;
        private String datasetVersionId;
        private EquityCurveOptions equityCurve;

        public Builder strategy(String strategy) { this.strategy = strategy; return this; }
        public Builder exchangeId(String exchangeId) { this.exchangeId = exchangeId; return this; }
        public Builder instrument(String instrument) { this.instrument = instrument; return this; }
        public Builder from(String from) { this.from = from; return this; }
        public Builder to(String to) { this.to = to; return this; }
        public Builder storeSignals(boolean storeSignals) { this.storeSignals = storeSignals; return this; }
        public Builder datasetId(String datasetId) { this.datasetId = datasetId; return this; }
        public Builder datasetVersionId(String datasetVersionId) { this.datasetVersionId = datasetVersionId; return this; }
        /**
         * @param equityCurve requested server-side transform for the inline result curve
         * @return this builder
         */
        public Builder equityCurve(EquityCurveOptions equityCurve) { this.equityCurve = equityCurve; return this; }

        public BacktestRequest build() {
            return new BacktestRequest(
                    strategy, exchangeId, instrument, from, to, storeSignals, datasetId, datasetVersionId, equityCurve);
        }
    }
}
