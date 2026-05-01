package net.qtsurfer.api.sdk;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.qtsurfer.api.sdk.errors.QTSDownloadError;
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
            exchange.getResponseHeaders().add("Content-Type", "application/vnd.lastra");
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
