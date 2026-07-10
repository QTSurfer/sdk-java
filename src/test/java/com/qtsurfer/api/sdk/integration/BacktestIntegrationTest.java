package com.qtsurfer.api.sdk.integration;

import com.qtsurfer.api.client.model.CoverageWindow;
import com.qtsurfer.api.client.model.InstrumentCoverage;
import com.qtsurfer.api.client.model.InstrumentDetail;
import com.qtsurfer.api.client.model.ResultMap;
import com.qtsurfer.api.sdk.BacktestOptions;
import com.qtsurfer.api.sdk.BacktestRequest;
import com.qtsurfer.api.sdk.BacktestStage;
import com.qtsurfer.api.sdk.QTSurfer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hits the real QTSurfer backend. Skipped unless {@code JWT_API_TOKEN} is set.
 *
 * <p>Configurable via env:
 * <ul>
 *   <li>{@code JWT_API_TOKEN} — bearer token (required)</li>
 *   <li>{@code QTSURFER_API_URL} — base URL (required)</li>
 *   <li>{@code QTSURFER_TEST_VERBOSE=1} — stream progress + final result to stdout</li>
 * </ul>
 */
@EnabledIfEnvironmentVariable(named = "JWT_API_TOKEN", matches = ".+")
@EnabledIfEnvironmentVariable(named = "QTSURFER_API_URL", matches = ".+")
class BacktestIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(BacktestIntegrationTest.class);

    private static final boolean VERBOSE =
            "1".equals(System.getenv("QTSURFER_TEST_VERBOSE"))
                    || "true".equalsIgnoreCase(System.getenv("QTSURFER_TEST_VERBOSE"));

    @Test
    void completesCompilePrepareExecuteAgainstBinanceBtcUsdt() throws Exception {
        String token = Objects.requireNonNull(System.getenv("JWT_API_TOKEN"), "JWT_API_TOKEN");
        String baseUrl = Objects.requireNonNull(System.getenv("QTSURFER_API_URL"), "QTSURFER_API_URL");

        QTSurfer qts = QTSurfer.builder()
                .baseUrl(baseUrl)
                .token(token)
                .build();

        // Honor the instrument's real data availability from the instruments API
        // instead of presupposing a fixed calendar day — the backend may hold only
        // a short recent window. Prefer ticker coverage (this is a TICKER backtest),
        // fall back to klines, and cap the span at 24h.
        CoverageWindow window = availableWindow(qts, "binance", "BTC/USDT");
        OffsetDateTime to = window.getTo();
        OffsetDateTime from = window.getFrom();
        if (Duration.between(from, to).compareTo(Duration.ofHours(24)) > 0) {
            from = to.minus(Duration.ofHours(24));
        }
        if (VERBOSE) {
            log.info("Backtest window (instrument coverage, capped at 24h): {} → {}", from, to);
        }

        BacktestRequest req = BacktestRequest.builder()
                .strategy(loadFixture("fixtures/ForcedTradeStrategy.java"))
                .exchangeId("binance")
                .instrument("BTC/USDT")
                .from(from.toInstant().toString())
                .to(to.toInstant().toString())
                .build();

        Set<BacktestStage> stages = new ConcurrentSkipListSet<>();
        BacktestOptions opts = BacktestOptions.builder()
                .pollInterval(Duration.ofMillis(500))
                .maxPollInterval(Duration.ofSeconds(3))
                .timeout(Duration.ofMinutes(5))
                .onProgress(p -> {
                    stages.add(p.stage());
                    if (VERBOSE) {
                        log.info("Progress: {} {}",
                                p.stage(),
                                p.percent() != null ? String.format("%.1f%%", p.percent()) : "");
                    }
                })
                .build();

        ResultMap result = qts.backtest(req, opts).get(5, TimeUnit.MINUTES);

        // Log full result before any assertion so CI always shows the response
        log.info("Result: {}", result);
        log.info("  instrument={} strategyId={} pnl={} trades={} winRate={} sharpe={} cagr={} maxDD={}%",
                result.getInstrument(), result.getStrategyId(),
                result.getPnlTotal(), result.getTotalTrades(),
                result.getWinRate(), result.getSharpeRatio(),
                result.getCagr(), result.getMaxDrawdownPercent());

        assertNotNull(result, "result");
        assertNotNull(result.getStrategyId(), "strategyId");
        assertEquals("BTC/USDT", result.getInstrument(), "instrument");
        assertTrue(stages.contains(BacktestStage.COMPILING), "compiling stage fired");
        assertTrue(stages.contains(BacktestStage.PREPARING), "preparing stage fired");
        assertTrue(stages.contains(BacktestStage.EXECUTING), "executing stage fired");
    }

    /**
     * Resolves the instrument's currently-available data window from the
     * instruments API, preferring ticker coverage and falling back to klines.
     */
    private static CoverageWindow availableWindow(QTSurfer qts, String exchangeId, String instrumentId) {
        InstrumentDetail instrument = qts.instruments(exchangeId).stream()
                .filter(i -> instrumentId.equals(i.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(instrumentId + " not listed on " + exchangeId));
        InstrumentCoverage coverage = instrument.getCoverage();
        assertNotNull(coverage, "coverage for " + instrumentId);
        CoverageWindow window = coverage.getTickers() != null ? coverage.getTickers() : coverage.getKlines();
        assertNotNull(window, "ticker/kline coverage window for " + instrumentId);
        assertNotNull(window.getFrom(), "coverage window from");
        assertNotNull(window.getTo(), "coverage window to");
        return window;
    }

    private static String loadFixture(String path) throws IOException {
        try (InputStream in = BacktestIntegrationTest.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IOException("Missing fixture " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
