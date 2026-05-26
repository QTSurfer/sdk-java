package com.qtsurfer.api.sdk.integration;

import com.qtsurfer.api.client.model.AuthTokenResponse;
import com.qtsurfer.api.sdk.QTSurfer;
import com.qtsurfer.api.sdk.auth.AuthOptions;
import com.qtsurfer.api.sdk.auth.AuthenticatedClient;
import com.qtsurfer.api.sdk.auth.TokenStore;
import com.qtsurfer.api.sdk.errors.QTSAuthError;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Offline integration test for the {@code auth(apikey)} helper.
 *
 * <p>Drives the helper through the public {@code QTSurfer.auth(...)} entry
 * point against a real {@link HttpServer} on a free port — exercising the
 * full ApiClient stack (JDK {@code HttpClient}, request interceptors,
 * Jackson decoding) without ever leaving the test JVM.
 *
 * <p>Mirrors the suffix convention of {@code BacktestIntegrationTest} so
 * Surefire's {@code *IntegrationTest} include picks it up, but unlike the
 * latter it does <em>not</em> require {@code JWT_API_TOKEN}.
 */
class AuthHelperIntegrationTest {

    private HttpServer server;
    private String baseUrl;
    private final List<String> tokenResponses = new ArrayList<>();
    private final AtomicReference<String> lastApikey = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] body;
            int status;
            if (path.endsWith("/auth/token")) {
                lastApikey.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
                if (tokenResponses.isEmpty()) {
                    status = 500;
                    body = "{}".getBytes(StandardCharsets.UTF_8);
                } else {
                    String resp = tokenResponses.remove(0);
                    if (resp.startsWith("401:")) {
                        status = 401;
                        body = resp.substring(4).getBytes(StandardCharsets.UTF_8);
                    } else {
                        status = 200;
                        body = resp.getBytes(StandardCharsets.UTF_8);
                    }
                }
            } else {
                status = 404;
                body = new byte[0];
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
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

    @Test
    void endToEnd_mintAndExposeToken() {
        tokenResponses.add(
                "{\"access_token\":\"jwt-int\",\"token_type\":\"Bearer\","
                + "\"expires_in\":3600,\"tier\":\"pro\"}");

        AuthenticatedClient session = QTSurfer.auth("ak_int",
                AuthOptions.builder().baseUrl(baseUrl).build());

        AuthTokenResponse token = session.token();
        assertNotNull(token);
        assertEquals("jwt-int", token.getAccessToken());
        assertEquals(AuthTokenResponse.TierEnum.PRO, token.getTier());
        assertEquals("ak_int", lastApikey.get());
    }

    @Test
    void endToEnd_tokenStorePluginReceivesSavedToken() {
        tokenResponses.add(
                "{\"access_token\":\"jwt-store\",\"token_type\":\"Bearer\","
                + "\"expires_in\":3600,\"tier\":\"free\"}");

        List<AuthTokenResponse> saved = new ArrayList<>();
        TokenStore store = new TokenStore() {
            @Override public AuthTokenResponse load() { return null; }
            @Override public void save(AuthTokenResponse t) { saved.add(t); }
            @Override public void clear() { saved.clear(); }
        };

        QTSurfer.auth("ak", AuthOptions.builder().baseUrl(baseUrl).store(store).build());
        assertEquals(1, saved.size());
        assertEquals("jwt-store", saved.get(0).getAccessToken());
    }

    @Test
    void endToEnd_mint401SurfacesAsQtsAuthError() {
        tokenResponses.add("401:{\"code\":\"invalid_apikey\",\"message\":\"x\"}");
        assertThrows(QTSAuthError.class,
                () -> QTSurfer.auth("ak_bad",
                        AuthOptions.builder().baseUrl(baseUrl).build()));
    }
}
