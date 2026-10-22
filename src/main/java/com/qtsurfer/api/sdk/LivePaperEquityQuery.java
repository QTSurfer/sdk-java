package com.qtsurfer.api.sdk;

import com.qtsurfer.api.client.model.EquityCurveOutMode;
import com.qtsurfer.api.client.model.LivePaperEquityCurve;

/** Options for reading a paper account or instrument equity curve. */
public record LivePaperEquityQuery(
        String currency,
        String instrument,
        LivePaperEquityCurve.MeasureEnum measure,
        Long sinceMs,
        EquityCurveOutMode outMode,
        Integer resample,
        Boolean differential,
        String cursor,
        Integer limit) {

    /** Preserve the original account, time-range, and cursor query constructor. */
    public LivePaperEquityQuery(String currency, Long sinceMs, String cursor, Integer limit) {
        this(currency, null, null, sinceMs, null, null, null, cursor, limit);
    }

    /** Create a builder for a paper-equity query. */
    public static Builder builder() { return new Builder(); }

    /** Fluent builder for {@link LivePaperEquityQuery}. */
    public static final class Builder {
        private String currency;
        private String instrument;
        private LivePaperEquityCurve.MeasureEnum measure;
        private Long sinceMs;
        private EquityCurveOutMode outMode;
        private Integer resample;
        private Boolean differential;
        private String cursor;
        private Integer limit;

        /** Select a quote-currency account; omit for a single account. */
        public Builder currency(String currency) { this.currency = currency; return this; }
        /** Select one instrument's PnL curve, or its account when accounts are split by instrument. */
        public Builder instrument(String instrument) { this.instrument = instrument; return this; }
        /** Select {@code realisedPnl} or {@code totalPnl} for an instrument curve. */
        public Builder measure(LivePaperEquityCurve.MeasureEnum measure) { this.measure = measure; return this; }
        /** Start at this market-time instant in epoch milliseconds. */
        public Builder sinceMs(Long sinceMs) { this.sinceMs = sinceMs; return this; }
        /** Request the ARRAY or SHORT shape; the resolved response mode is in {@code meta}. */
        public Builder outMode(EquityCurveOutMode outMode) { this.outMode = outMode; return this; }
        /** Downsample to at most this many points. Cannot be combined with paging. */
        public Builder resample(Integer resample) { this.resample = resample; return this; }
        /** Delta-encode values from the second point onward. */
        public Builder differential(Boolean differential) { this.differential = differential; return this; }
        /** Continue from an opaque server-provided cursor. */
        public Builder cursor(String cursor) { this.cursor = cursor; return this; }
        /** Request a raw page; omit for a complete or resampled curve. */
        public Builder limit(Integer limit) { this.limit = limit; return this; }

        /** Build the query; omitted values use API defaults. */
        public LivePaperEquityQuery build() {
            return new LivePaperEquityQuery(
                    currency, instrument, measure, sinceMs, outMode, resample, differential, cursor, limit);
        }
    }
}
