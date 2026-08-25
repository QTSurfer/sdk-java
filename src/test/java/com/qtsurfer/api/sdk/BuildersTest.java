package com.qtsurfer.api.sdk;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildersTest {

    @Test
    void backtestRequestBuilderPopulatesAllFields() {
        BacktestRequest r = BacktestRequest.builder()
                .strategy("class S {}")
                .exchangeId("binance")
                .instrument("BTC/USDT")
                .from("2026-04-13T00:00:00Z")
                .to("2026-04-14T00:00:00Z")
                .storeSignals(true)
                .build();

        assertEquals("class S {}", r.strategy());
        assertEquals("binance", r.exchangeId());
        assertEquals("BTC/USDT", r.instrument());
        assertEquals("2026-04-13T00:00:00Z", r.from());
        assertEquals("2026-04-14T00:00:00Z", r.to());
        assertEquals(Boolean.TRUE, r.storeSignals());
    }

    @Test
    void backtestRequestRejectsNullRequiredFields() {
        assertThrows(NullPointerException.class,
                () -> new BacktestRequest(null, "binance", "BTC/USDT", "2026-01-01", "2026-01-02", null, null, null));
        assertThrows(NullPointerException.class,
                () -> new BacktestRequest("s", null, "BTC/USDT", "2026-01-01", "2026-01-02", null, null, null));
    }

    @Test
    void backtestRequestBuildsFromDatasetIdAlone() {
        BacktestRequest r = BacktestRequest.builder()
                .strategy("class S {}")
                .exchangeId("user")
                .datasetId("ds_x")
                .from("2026-04-13T00:00:00Z")
                .to("2026-04-14T00:00:00Z")
                .build();

        assertNull(r.instrument());
        assertEquals("ds_x", r.datasetId());
        assertNull(r.datasetVersionId());
    }

    @Test
    void backtestRequestRejectsBothInstrumentAndDatasetId() {
        assertThrows(IllegalArgumentException.class, () -> BacktestRequest.builder()
                .strategy("class S {}")
                .exchangeId("binance")
                .instrument("BTC/USDT")
                .datasetId("ds_x")
                .from("2026-04-13T00:00:00Z")
                .to("2026-04-14T00:00:00Z")
                .build());
    }

    @Test
    void backtestRequestRejectsNeitherInstrumentNorDatasetId() {
        assertTrue(assertThrows(NullPointerException.class, () -> BacktestRequest.builder()
                .strategy("class S {}")
                .exchangeId("binance")
                .from("2026-04-13T00:00:00Z")
                .to("2026-04-14T00:00:00Z")
                .build()
        ).getMessage().contains("instrument"));
    }

    @Test
    void backtestRequestRejectsDatasetVersionIdWithoutDatasetId() {
        assertThrows(IllegalArgumentException.class, () -> BacktestRequest.builder()
                .strategy("class S {}")
                .exchangeId("binance")
                .instrument("BTC/USDT")
                .datasetVersionId("dsv_x")
                .from("2026-04-13T00:00:00Z")
                .to("2026-04-14T00:00:00Z")
                .build());
    }

    @Test
    void backtestOptionsDefaultsHaveSaneValues() {
        BacktestOptions opts = BacktestOptions.defaults();
        assertEquals(BacktestOptions.DEFAULT_POLL_INTERVAL, opts.pollInterval());
        assertEquals(BacktestOptions.DEFAULT_MAX_POLL_INTERVAL, opts.maxPollInterval());
        assertNull(opts.onProgress());
        assertNull(opts.timeout());
    }

    @Test
    void backtestOptionsBuilderOverrides() {
        BacktestOptions opts = BacktestOptions.builder()
                .pollInterval(Duration.ofMillis(100))
                .maxPollInterval(Duration.ofSeconds(2))
                .timeout(Duration.ofMinutes(5))
                .onProgress(p -> {})
                .build();

        assertEquals(Duration.ofMillis(100), opts.pollInterval());
        assertEquals(Duration.ofSeconds(2), opts.maxPollInterval());
        assertEquals(Duration.ofMinutes(5), opts.timeout());
        assertNotNull(opts.onProgress());
    }

    @Test
    void backtestOptionsFallsBackToDefaultsWhenNullPassed() {
        BacktestOptions opts = new BacktestOptions(null, null, null, null);
        assertEquals(BacktestOptions.DEFAULT_POLL_INTERVAL, opts.pollInterval());
        assertEquals(BacktestOptions.DEFAULT_MAX_POLL_INTERVAL, opts.maxPollInterval());
    }

    @Test
    void qtsurferOptionsBuilderAcceptsStringBaseUrl() {
        QTSurferOptions opts = QTSurferOptions.builder()
                .baseUrl("https://api.qtsurfer.com/v1")
                .token("t")
                .build();
        assertEquals("https://api.qtsurfer.com/v1", opts.baseUrl().toString());
        assertEquals("t", opts.token());
        assertNull(opts.httpClient());
    }

    @Test
    void qtsurferOptionsRejectsNullBaseUrl() {
        assertTrue(assertThrows(NullPointerException.class,
                () -> new QTSurferOptions(null, "t", null, null)
        ).getMessage().contains("baseUrl"));
    }
}
