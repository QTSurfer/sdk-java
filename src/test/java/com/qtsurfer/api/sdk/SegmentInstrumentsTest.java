package com.qtsurfer.api.sdk;

import com.qtsurfer.api.client.model.InstrumentDetail;
import com.qtsurfer.api.sdk.errors.QTSError;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the per-segment instrument listing. All HTTP traffic is
 * served by a local {@link HttpServer} on a free port — no live network
 * calls.
 */
class SegmentInstrumentsTest {

    private static final String ENVELOPE = """
            {"data":[{"id":"BTC/USDT","base":"BTC","quote":"USDT","lastPrice":84250.50,\
            "coverage":{"tickers":{"from":"2026-04-10T21:00:00Z","to":"2026-07-09T20:29:05Z"}}},\
            {"id":"ETH/USDT","base":"ETH","quote":"USDT","lastPrice":3120.25}],\
            "meta":{"count":2},"_links":{"self":{"href":"/exchange/binance/futures/instruments"}}}""";

    private HttpServer server;
    private QTSurfer qts;
    private final List<HttpExchange> exchanges = new ArrayList<>();
    private final AtomicReference<String> responseBody = new AtomicReference<>(ENVELOPE);
    private final AtomicReference<Integer> responseStatus = new AtomicReference<>(200);

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchanges.add(exchange);
            byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus.get(), body.length == 0 ? -1 : body.length);
            try (var os = exchange.getResponseBody()) {
                if (body.length > 0) os.write(body);
            }
        });
        server.start();
        qts = QTSurfer.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .token("test-token")
                .build();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void segmentOverloadHitsTheSegmentPath() {
        qts.instruments("binance", "futures");

        HttpExchange recorded = exchanges.get(0);
        assertEquals("GET", recorded.getRequestMethod());
        assertEquals("/exchange/binance/futures/instruments", recorded.getRequestURI().getPath());
        assertEquals("Bearer test-token", recorded.getRequestHeaders().getFirst("Authorization"));
    }

    @Test
    void singleArgOverloadKeepsHittingTheDefaultSegmentShortcut() {
        qts.instruments("binance");

        assertEquals("/exchange/binance/instruments", exchanges.get(0).getRequestURI().getPath());
    }

    /** Same HAL envelope as {@code instruments(exchangeId)} — unwrapped the same way. */
    @Test
    void unwrapsTheHalEnvelopeToTheInstrumentList() {
        List<InstrumentDetail> instruments = qts.instruments("binance", "spot");

        assertEquals(2, instruments.size());
        assertEquals("BTC/USDT", instruments.get(0).getId());
        assertEquals("BTC", instruments.get(0).getBase());
        assertEquals(
                OffsetDateTime.parse("2026-04-10T21:00:00Z"),
                instruments.get(0).getCoverage().getTickers().getFrom());
        assertEquals("ETH/USDT", instruments.get(1).getId());
        assertNull(instruments.get(1).getCoverage());
    }

    @Test
    void mapsApiExceptionToQtsError() {
        responseStatus.set(404);
        responseBody.set("{\"code\":\"NOT_FOUND\",\"message\":\"unknown exchange\"}");

        QTSError ex = assertThrows(QTSError.class, () -> qts.instruments("nope", "futures"));
        assertTrue(ex.getMessage().contains("instruments call failed"));
        assertTrue(ex.getMessage().contains("HTTP 404"));
        assertTrue(ex.getMessage().contains("NOT_FOUND"));
    }

    @Test
    void rejectsNullArguments() {
        assertThrows(NullPointerException.class, () -> qts.instruments("binance", null));
        assertThrows(NullPointerException.class, () -> qts.instruments(null, "spot"));
    }
}
