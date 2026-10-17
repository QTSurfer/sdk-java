package com.qtsurfer.api.sdk;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.qtsurfer.api.sdk.errors.QTSDownloadError;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadsTest {

    @Test
    void metadataIncludesProvisionalBoundaryAndUntouchedBytes() throws Exception {
        responseBody.set(new byte[] {0, -1, -128});
        try (var response = qts.downloadTickersWithMetadata("binance", "BTC", "USDT", "2026-10-17T10", null)) {
            assertArrayEquals(new byte[] {0, -1, -128}, response.body().readAllBytes());
            assertEquals("micros", response.timestampUnit().orElseThrow());
            assertEquals("provisional", response.dataStatus().orElseThrow());
            assertEquals("2026-10-17T10:30:00Z", response.dataThrough().orElseThrow());
        }
    }

    @Test
    void typedDownloadReasonsRecoveryAndRetryHeadersSurvive() {
        for (String reason : List.of("OUTSIDE_WINDOW", "NOT_YET_AVAILABLE", "NO_DATA", "TEMPORARILY_UNAVAILABLE")) {
            responseStatus.set(reason.equals("OUTSIDE_WINDOW") ? 400
                    : reason.equals("TEMPORARILY_UNAVAILABLE") ? 503 : 404);
            responseBody.set(("{\"message\":\"request failed\",\"reason\":\"" + reason
                    + "\",\"next\":{\"method\":\"POST\",\"path\":\"/datasets/imports\",\"body\":{\"type\":\"cex\"}}}")
                    .getBytes(StandardCharsets.UTF_8));
            var error = assertThrows(QTSDownloadError.class,
                    () -> qts.downloadKlinesWithMetadata("binance", "BTC", "USDT", "2026-10-17T10", null));
            assertEquals(reason, error.getReason().orElseThrow());
            assertEquals(responseStatus.get().intValue(), error.getStatusCode().orElseThrow());
            assertEquals("/datasets/imports", error.getNext().orElseThrow().getPath());
            assertEquals("cex", error.getNext().orElseThrow().getBody().get("type"));
            assertEquals("30", error.getResponseHeaders().orElseThrow().firstValue("Retry-After").orElseThrow());
        }
    }

    private HttpServer server;
    private QTSurfer qts;
    private final List<HttpExchange> exchanges = new ArrayList<>();
    private final AtomicReference<byte[]> responseBody = new AtomicReference<>(new byte[0]);
    private final AtomicReference<Integer> responseStatus = new AtomicReference<>(200);

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchanges.add(exchange);
            byte[] body = responseBody.get();
            int status = responseStatus.get();
            exchange.getResponseHeaders().add("Content-Type", "application/vnd.qtsurfer.lastra");
            exchange.getResponseHeaders().add("X-Ts-Unit", "micros");
            exchange.getResponseHeaders().add("X-Data-Status", "provisional");
            exchange.getResponseHeaders().add("X-Data-Through", "2026-10-17T10:30:00Z");
            exchange.getResponseHeaders().add("Retry-After", "30");
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
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
    void tickersDefaultsToLastra() throws IOException {
        byte[] payload = "LASTRA-PAYLOAD".getBytes(StandardCharsets.UTF_8);
        responseBody.set(payload);

        try (InputStream in = qts.tickers("binance", "BTC", "USDT", "2026-01-15T10")) {
            assertArrayEquals(payload, in.readAllBytes());
        }

        HttpExchange recorded = exchanges.get(0);
        assertEquals("/exchange/binance/tickers/BTC/USDT", recorded.getRequestURI().getPath());
        assertEquals("hour=2026-01-15T10&format=lastra", recorded.getRequestURI().getRawQuery());
        assertEquals("Bearer test-token", recorded.getRequestHeaders().getFirst("Authorization"));
    }

    @Test
    void klinesEmitsParquetFormatWhenRequested() throws IOException {
        responseBody.set("ok".getBytes(StandardCharsets.UTF_8));

        try (InputStream in = qts.klines("binance", "BTC", "USDT", "2026-01-15T10", DownloadFormat.PARQUET)) {
            in.readAllBytes();
        }

        HttpExchange recorded = exchanges.get(0);
        assertEquals("/exchange/binance/klines/BTC/USDT", recorded.getRequestURI().getPath());
        assertEquals("hour=2026-01-15T10&format=parquet", recorded.getRequestURI().getRawQuery());
    }

    @Test
    void mapsApiExceptionToQtsDownloadError() {
        responseStatus.set(404);
        responseBody.set("{\"code\":\"NOT_FOUND\",\"message\":\"hour not backfilled\"}"
                .getBytes(StandardCharsets.UTF_8));

        QTSDownloadError ex = assertThrows(
                QTSDownloadError.class,
                () -> qts.tickers("binance", "BTC", "USDT", "2026-01-15T10"));
        assertTrue(ex.getMessage().contains("HTTP 404"));
        assertTrue(ex.getMessage().contains("NOT_FOUND"));
    }
}
