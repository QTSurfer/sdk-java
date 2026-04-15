package net.qtsurfer.api.sdk.integration;

import net.qtsurfer.api.client.model.ResultMap;
import net.qtsurfer.api.sdk.BacktestOptions;
import net.qtsurfer.api.sdk.BacktestRequest;
import net.qtsurfer.api.sdk.BacktestStage;
import net.qtsurfer.api.sdk.QTSurfer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 *   <li>{@code QTSURFER_API_URL} — base URL (defaults to pre: https://api.qtsurfer.net/v1)</li>
 *   <li>{@code QTSURFER_TEST_VERBOSE=1} — stream progress + final result to stdout</li>
 * </ul>
 */
@EnabledIfEnvironmentVariable(named = "JWT_API_TOKEN", matches = ".+")
class BacktestIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(BacktestIntegrationTest.class);

    private static final String DEFAULT_BASE_URL = "https://api.qtsurfer.net/v1";
    private static final boolean VERBOSE =
            "1".equals(System.getenv("QTSURFER_TEST_VERBOSE"))
                    || "true".equalsIgnoreCase(System.getenv("QTSURFER_TEST_VERBOSE"));

    @Test
    void completesCompilePrepareExecuteAgainstBinanceBtcUsdt() throws Exception {
        String token = Objects.requireNonNull(System.getenv("JWT_API_TOKEN"), "JWT_API_TOKEN");
        String baseUrl = Objects.requireNonNullElse(System.getenv("QTSURFER_API_URL"), DEFAULT_BASE_URL);

        QTSurfer qts = QTSurfer.builder()
                .baseUrl(baseUrl)
                .token(token)
                .build();

        BacktestRequest req = BacktestRequest.builder()
                .strategy(loadFixture("fixtures/ForcedTradeStrategy.java"))
                .exchangeId("binance")
                .instrument("BTC/USDT")
                .from(dayStartIso(1))
                .to(dayStartIso(0))
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

        assertNotNull(result, "result");
        assertNotNull(result.getStrategyId(), "strategyId");
        assertEquals("BTC/USDT", result.getInstrument(), "instrument");
        assertTrue(stages.contains(BacktestStage.COMPILING), "compiling stage fired");
        assertTrue(stages.contains(BacktestStage.PREPARING), "preparing stage fired");
        assertTrue(stages.contains(BacktestStage.EXECUTING), "executing stage fired");

        if (VERBOSE) {
            log.info("Result: {}", result);
        }
    }

    private static String dayStartIso(int offsetDays) {
        return Instant.now()
                .truncatedTo(ChronoUnit.DAYS)
                .minus(offsetDays, ChronoUnit.DAYS)
                .toString();
    }

    private static String loadFixture(String path) throws IOException {
        try (InputStream in = BacktestIntegrationTest.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IOException("Missing fixture " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
