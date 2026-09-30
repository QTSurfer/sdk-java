package com.qtsurfer.api.sdk;

/** Optional account, time-range, and cursor filters for paper-equity history. */
public record LivePaperEquityQuery(String currency, Long sinceMs, String cursor, Integer limit) {

    /** Create a builder for a paper-equity query. */
    public static Builder builder() { return new Builder(); }

    /** Fluent builder for {@link LivePaperEquityQuery}. */
    public static final class Builder {
        private String currency;
        private Long sinceMs;
        private String cursor;
        private Integer limit;

        /** Select one quote-currency account; omit to combine all accounts. */
        public Builder currency(String currency) { this.currency = currency; return this; }
        /** Start at this market-time instant in epoch milliseconds. */
        public Builder sinceMs(Long sinceMs) { this.sinceMs = sinceMs; return this; }
        /** Continue from an opaque server-provided cursor. */
        public Builder cursor(String cursor) { this.cursor = cursor; return this; }
        /** Set the page size; the API defaults to 100 and caps at 1000. */
        public Builder limit(Integer limit) { this.limit = limit; return this; }

        /** Build the query; omitted values use API defaults. */
        public LivePaperEquityQuery build() {
            return new LivePaperEquityQuery(currency, sinceMs, cursor, limit);
        }
    }
}
