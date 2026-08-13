package com.qtsurfer.api.sdk.auth;

import com.qtsurfer.api.client.model.AuthTokenResponse;
import com.qtsurfer.api.client.model.InstrumentDetail;
import com.qtsurfer.api.client.model.ResultMap;
import com.qtsurfer.api.sdk.BacktestOutcome;
import com.qtsurfer.api.sdk.QTSurfer;
import com.qtsurfer.api.sdk.ValidationOutcome;
import com.qtsurfer.api.sdk.errors.QTSAuthError;
import com.qtsurfer.api.sdk.errors.QTSDownloadError;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the apikey -> JWT auth helper. All HTTP traffic is served
 * by a local {@link HttpServer} on a free port — no live network calls.
 */
class AuthenticatedClientTest {

    private HttpServer server;
    private String baseUrl;
    private final List<RequestRecord> requests = new ArrayList<>();
    private final List<String> tokenResponses = new ArrayList<>();
    private final AtomicInteger tokenCalls = new AtomicInteger();
    private final List<Integer> tickerStatuses = new ArrayList<>();
    private final AtomicInteger tickerCalls = new AtomicInteger();
    private final List<Integer> validateStatuses = new ArrayList<>();
    private final AtomicInteger validateCalls = new AtomicInteger();
    private final List<Integer> resultStatuses = new ArrayList<>();
    private final AtomicInteger resultCalls = new AtomicInteger();
    private final List<Integer> compileStatuses = new ArrayList<>();
    private final AtomicInteger compileCalls = new AtomicInteger();

    record RequestRecord(String path, String method, String authorization, String apikey) {}

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(new RequestRecord(
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestMethod(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("X-API-Key")));
            String path = exchange.getRequestURI().getPath();
            byte[] body;
            int status;
            String ctype;
            if (path.endsWith("/auth/token")) {
                int idx = tokenCalls.getAndIncrement();
                String resp = idx < tokenResponses.size()
                        ? tokenResponses.get(idx)
                        : tokenResponses.get(tokenResponses.size() - 1);
                if (resp.startsWith("401:")) {
                    status = 401;
                    body = resp.substring(4).getBytes(StandardCharsets.UTF_8);
                } else {
                    status = 200;
                    body = resp.getBytes(StandardCharsets.UTF_8);
                }
                ctype = "application/json";
            } else if (path.contains("/tickers/")) {
                int idx = tickerCalls.getAndIncrement();
                status = idx < tickerStatuses.size()
                        ? tickerStatuses.get(idx)
                        : tickerStatuses.get(tickerStatuses.size() - 1);
                body = status == 200
                        ? "LASTRA-OK".getBytes(StandardCharsets.UTF_8)
                        : ("{\"error\":\"" + status + "\"}").getBytes(StandardCharsets.UTF_8);
                ctype = status == 200 ? "application/vnd.lastra" : "application/json";
            } else if (path.endsWith("/validate")) {
                int idx = validateCalls.getAndIncrement();
                status = idx < validateStatuses.size()
                        ? validateStatuses.get(idx)
                        : validateStatuses.get(validateStatuses.size() - 1);
                body = (status == 202
                        ? "{\"strategyId\":\"s1\",\"validation\":\"pending\"}"
                        : "{\"error\":\"" + status + "\"}").getBytes(StandardCharsets.UTF_8);
                ctype = "application/json";
            } else if (path.contains("/execute/")) {
                int idx = resultCalls.getAndIncrement();
                status = idx < resultStatuses.size()
                        ? resultStatuses.get(idx)
                        : resultStatuses.get(resultStatuses.size() - 1);
                body = (status == 200
                        ? "{\"state\":{\"status\":\"Completed\"},\"results\":{\"pnlTotal\":42.5}}"
                        : "{\"error\":\"" + status + "\"}").getBytes(StandardCharsets.UTF_8);
                ctype = "application/json";
            } else if (path.endsWith("/strategy")) {
                int idx = compileCalls.getAndIncrement();
                status = idx < compileStatuses.size()
                        ? compileStatuses.get(idx)
                        : compileStatuses.get(compileStatuses.size() - 1);
                body = (status == 200
                        ? "{\"strategyId\":\"s1\"}"
                        : "{\"error\":\"" + status + "\"}").getBytes(StandardCharsets.UTF_8);
                ctype = "application/json";
            } else if (path.endsWith("/instruments")) {
                status = 200;
                body = ("{\"data\":[{\"id\":\"BTC/USDT\",\"base\":\"BTC\",\"quote\":\"USDT\"}],"
                        + "\"meta\":{},\"_links\":{}}").getBytes(StandardCharsets.UTF_8);
                ctype = "application/json";
            } else {
                status = 404;
                body = new byte[0];
                ctype = "application/json";
            }
            exchange.getResponseHeaders().add("Content-Type", ctype);
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            try (var os = exchange.getResponseBody()) {
                if (body.length > 0) os.write(body);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static String jwt(String access, String tier) {
        return jwt(access, tier, 3600);
    }

    private static String jwt(String access, String tier, int expiresIn) {
        return "{\"access_token\":\"" + access
                + "\",\"token_type\":\"Bearer\",\"expires_in\":" + expiresIn + ",\"tier\":\""
                + tier + "\"}";
    }

    private AuthOptions opts() {
        return AuthOptions.builder().baseUrl(baseUrl).build();
    }

    @Test
    void usesExplicitApikeyForTheMintCall() {
        tokenResponses.add(jwt("jwt-explicit", "free"));
        AuthenticatedClient session = QTSurfer.authenticate("ak_explicit", opts());
        assertEquals("jwt-explicit", session.token().getAccessToken());
        assertEquals(1, tokenCalls.get());
        RequestRecord first = requests.get(0);
        assertEquals("ak_explicit", first.apikey());
        assertEquals("POST", first.method());
    }

    @Test
    void readsApikeyFromEnvWhenNoArgPassed() {
        // End-to-end: a null arg with no env raises QTSAuthError.
        AuthOptions o = opts();
        QTSAuthError ex = assertThrows(QTSAuthError.class, () -> QTSurfer.authenticate(null, o));
        assertTrue(ex.getMessage().contains("QTSURFER_APIKEY"));
    }

    @Test
    void resolveApikeyPrefersExplicitOverEnv() {
        // env-var resolution is unit-tested via the package-private overload
        // (the process env can't be mutated portably in a JUnit test).
        assertEquals("ak_arg",
                AuthenticatedClient.resolveApikey("ak_arg", "ak_env"));
        assertEquals("ak_env",
                AuthenticatedClient.resolveApikey(null, "ak_env"));
        assertEquals("ak_env",
                AuthenticatedClient.resolveApikey("", "ak_env"));
        assertEquals("ak_env",
                AuthenticatedClient.resolveApikey("   ", "ak_env"));
        assertThrows(QTSAuthError.class,
                () -> AuthenticatedClient.resolveApikey(null, null));
        assertThrows(QTSAuthError.class,
                () -> AuthenticatedClient.resolveApikey(null, ""));
    }

    @Test
    void blankApikeyTreatedAsMissing() {
        QTSAuthError ex = assertThrows(QTSAuthError.class, () -> QTSurfer.authenticate("   ", opts()));
        assertTrue(ex.getMessage().contains("apikey"));
    }

    @Test
    void mintFailureSurfacesAsQtsAuthError() {
        tokenResponses.add("401:{\"code\":\"invalid_apikey\",\"message\":\"no\"}");
        QTSAuthError ex = assertThrows(QTSAuthError.class,
                () -> QTSurfer.authenticate("ak_bad", opts()));
        assertTrue(ex.getMessage().contains("401"));
    }

    @Test
    void saveCalledOnTokenStoreOnEachMint() {
        tokenResponses.add(jwt("jwt-stored", "elite"));
        AtomicReference<AuthTokenResponse> stored = new AtomicReference<>();
        AtomicInteger saves = new AtomicInteger();
        TokenStore store = new TokenStore() {
            @Override public AuthTokenResponse load() { return null; }
            @Override public void save(AuthTokenResponse t) { stored.set(t); saves.incrementAndGet(); }
            @Override public void clear() { stored.set(null); }
        };
        QTSurfer.authenticate("ak", AuthOptions.builder().baseUrl(baseUrl).store(store).build());
        assertEquals(1, saves.get());
        assertNotNull(stored.get());
        assertEquals("jwt-stored", stored.get().getAccessToken());
    }

    @Test
    void seedsFromTokenStoreWithoutMintingWhenLoadReturnsToken() {
        AuthTokenResponse cached = new AuthTokenResponse();
        cached.setAccessToken("jwt-cached");
        cached.setTokenType(AuthTokenResponse.TokenTypeEnum.BEARER);
        cached.setExpiresIn(3600);
        TokenStore store = new TokenStore() {
            @Override public AuthTokenResponse load() { return cached; }
            @Override public void save(AuthTokenResponse t) {}
            @Override public void clear() {}
        };
        // No tokenResponses primed; if the SDK mints, the server returns 404.
        AuthenticatedClient session = QTSurfer.authenticate("ak",
                AuthOptions.builder().baseUrl(baseUrl).store(store).build());
        assertEquals("jwt-cached", session.token().getAccessToken());
        assertEquals(0, tokenCalls.get());
    }

    @Test
    void refreshOn401RetriesAndSucceeds() throws IOException {
        tokenResponses.add(jwt("jwt-1", "free"));
        tokenResponses.add(jwt("jwt-2", "free"));
        tickerStatuses.add(401);
        tickerStatuses.add(200);

        AuthenticatedClient session = QTSurfer.authenticate("ak", opts());
        try (InputStream in = session.tickers("binance", "BTC", "USDT", "2026-01-15T10")) {
            byte[] all = in.readAllBytes();
            assertEquals("LASTRA-OK", new String(all, StandardCharsets.UTF_8));
        }
        // 2 mints: initial + refresh.
        assertEquals(2, tokenCalls.get());
        // 2 ticker calls: original (401) + retry (200).
        assertEquals(2, tickerCalls.get());
        // Second ticker carried the refreshed JWT.
        RequestRecord retried = requests.stream()
                .filter(r -> r.path().contains("/tickers/"))
                .reduce((a, b) -> b)
                .orElseThrow();
        assertEquals("Bearer jwt-2", retried.authorization());
    }

    @Test
    void refreshOn401FailsWhenRetryAlso401() {
        tokenResponses.add(jwt("jwt-1", "free"));
        tokenResponses.add(jwt("jwt-2", "free"));
        tickerStatuses.add(401);
        tickerStatuses.add(401);

        AuthenticatedClient session = QTSurfer.authenticate("ak", opts());
        assertThrows(QTSDownloadError.class,
                () -> session.tickers("binance", "BTC", "USDT", "2026-01-15T10"));
        assertEquals(2, tokenCalls.get());
        assertEquals(2, tickerCalls.get());
    }

    @Test
    void proactiveRefreshBeforeExpiryMintsAgainWithoutA401() throws IOException {
        // expires_in (5s) is well inside the fixed REFRESH_SKEW (30s), so the very first
        // token is already treated as due for renewal by the time the next call checks it.
        tokenResponses.add(jwt("jwt-1", "free", 5));
        tokenResponses.add(jwt("jwt-2", "free"));

        AuthenticatedClient session = QTSurfer.authenticate("ak", opts());
        assertEquals("jwt-1", session.token().getAccessToken());

        List<InstrumentDetail> instruments = session.instruments("binance");
        assertEquals(1, instruments.size());

        // Refreshed proactively (no 401 anywhere): initial mint + one more before the call.
        assertEquals(2, tokenCalls.get());
        assertEquals("jwt-2", session.token().getAccessToken());
        RequestRecord instrumentsCall = requests.stream()
                .filter(r -> r.path().endsWith("/instruments"))
                .findFirst()
                .orElseThrow();
        assertEquals("Bearer jwt-2", instrumentsCall.authorization());
    }

    @Test
    void freshTokenIsNotProactivelyRefreshed() {
        tokenResponses.add(jwt("jwt-1", "free")); // expires_in: 3600, well outside REFRESH_SKEW

        AuthenticatedClient session = QTSurfer.authenticate("ak", opts());
        session.instruments("binance");

        // No proactive refresh — the one mint from authenticate() is still good.
        assertEquals(1, tokenCalls.get());
        assertEquals("jwt-1", session.token().getAccessToken());
    }

    @Test
    void compileParticipatesInRefreshOn401() {
        // Reproduces the reported bug: compile talks to its endpoint directly rather than
        // through the generated client, and used to throw with no recognizable cause on a
        // 401, so this retry never fired for it specifically.
        tokenResponses.add(jwt("jwt-1", "free"));
        tokenResponses.add(jwt("jwt-2", "free"));
        compileStatuses.add(401);
        compileStatuses.add(200);

        AuthenticatedClient session = QTSurfer.authenticate("ak", opts());
        var strategy = session.compile("public class S {}").join();
        assertEquals("s1", strategy.id());

        assertEquals(2, tokenCalls.get());
        assertEquals(2, compileCalls.get());
        RequestRecord retried = requests.stream()
                .filter(r -> r.path().endsWith("/strategy"))
                .reduce((a, b) -> b)
                .orElseThrow();
        assertEquals("Bearer jwt-2", retried.authorization());
    }

    @Test
    void non401ErrorsAreNotRetried() {
        tokenResponses.add(jwt("jwt-1", "free"));
        tickerStatuses.add(404);

        AuthenticatedClient session = QTSurfer.authenticate("ak", opts());
        assertThrows(QTSDownloadError.class,
                () -> session.tickers("binance", "BTC", "USDT", "2026-01-15T10"));
        // Only the initial mint — no refresh.
        assertEquals(1, tokenCalls.get());
        assertEquals(1, tickerCalls.get());
    }

    @Test
    void validateStrategyParticipatesInRefreshOn401() {
        tokenResponses.add(jwt("jwt-1", "free"));
        tokenResponses.add(jwt("jwt-2", "free"));
        validateStatuses.add(401);
        validateStatuses.add(202);

        AuthenticatedClient session = QTSurfer.authenticate("ak", opts());
        ValidationOutcome outcome = session.validateStrategy("s1");

        // The retried call answered 202: it queued a check.
        assertTrue(outcome.queued());
        assertInstanceOf(ValidationOutcome.Queued.class, outcome);
        assertEquals("s1", outcome.strategyId());
        assertEquals(2, tokenCalls.get());
        assertEquals(2, validateCalls.get());
        RequestRecord retried = requests.stream()
                .filter(r -> r.path().endsWith("/validate"))
                .reduce((a, b) -> b)
                .orElseThrow();
        assertEquals("POST", retried.method());
        assertEquals("/v1/strategy/s1/validate", retried.path());
        assertEquals("Bearer jwt-2", retried.authorization());
    }

    @Test
    void backtestResultParticipatesInRefreshOn401() {
        tokenResponses.add(jwt("jwt-1", "free"));
        tokenResponses.add(jwt("jwt-2", "free"));
        resultStatuses.add(401);
        resultStatuses.add(200);

        AuthenticatedClient session = QTSurfer.authenticate("ak", opts());
        BacktestOutcome outcome = session.backtestResult("binance", "exec-1");

        ResultMap result = assertInstanceOf(BacktestOutcome.Completed.class, outcome).results();
        assertEquals(42.5, result.getPnlTotal());
        assertEquals(2, tokenCalls.get());
        assertEquals(2, resultCalls.get());
        RequestRecord retried = requests.stream()
                .filter(r -> r.path().contains("/execute/"))
                .reduce((a, b) -> b)
                .orElseThrow();
        assertEquals("GET", retried.method());
        assertEquals("/v1/backtest/binance/ticker/execute/exec-1", retried.path());
        assertEquals("Bearer jwt-2", retried.authorization());
    }

    @Test
    void segmentInstrumentsHitsTheSegmentPathAndUnwrapsTheEnvelope() {
        tokenResponses.add(jwt("jwt-1", "free"));

        AuthenticatedClient session = QTSurfer.authenticate("ak", opts());
        List<InstrumentDetail> instruments = session.instruments("binance", "futures");

        assertEquals(1, instruments.size());
        assertEquals("BTC/USDT", instruments.get(0).getId());
        RequestRecord recorded = requests.stream()
                .filter(r -> r.path().endsWith("/instruments"))
                .reduce((a, b) -> b)
                .orElseThrow();
        assertEquals("/v1/exchange/binance/futures/instruments", recorded.path());
    }

    @Test
    void clearWipesTheCachedTokenAndStore() {
        tokenResponses.add(jwt("jwt-c", "free"));
        AtomicReference<AuthTokenResponse> stored = new AtomicReference<>();
        TokenStore store = new TokenStore() {
            @Override public AuthTokenResponse load() { return null; }
            @Override public void save(AuthTokenResponse t) { stored.set(t); }
            @Override public void clear() { stored.set(null); }
        };
        AuthenticatedClient session = QTSurfer.authenticate("ak",
                AuthOptions.builder().baseUrl(baseUrl).store(store).build());
        assertNotNull(session.token());
        assertNotNull(stored.get());

        session.clear();
        assertNull(session.token());
        assertNull(stored.get());
    }
}
