package com.qtsurfer.api.sdk;

/** Optional filters and cursor for one page of retained live signals. */
public record LiveSignalsQuery(Long sinceMs, String instrument, String type, String cursor, Integer limit) {

    /** Create a builder for a retained-signal query. */
    public static Builder builder() { return new Builder(); }

    /** Fluent builder for {@link LiveSignalsQuery}. */
    public static final class Builder {
        private Long sinceMs;
        private String instrument;
        private String type;
        private String cursor;
        private Integer limit;

        /** Set the inclusive lower event-time bound in epoch milliseconds. */
        public Builder sinceMs(Long sinceMs) { this.sinceMs = sinceMs; return this; }
        /** Filter to one instrument, wildcard, or comma-separated instruments. */
        public Builder instrument(String instrument) { this.instrument = instrument; return this; }
        /** Filter by signal type, such as {@code paper}. */
        public Builder type(String type) { this.type = type; return this; }
        /** Continue from an opaque server-provided cursor. */
        public Builder cursor(String cursor) { this.cursor = cursor; return this; }
        /** Set the maximum number of signals in the page. */
        public Builder limit(Integer limit) { this.limit = limit; return this; }

        /** Build the query; omitted values use API defaults. */
        public LiveSignalsQuery build() {
            return new LiveSignalsQuery(sinceMs, instrument, type, cursor, limit);
        }
    }
}
