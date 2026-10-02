package com.qtsurfer.api.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.qtsurfer.api.client.model.LiveParamsUpdateResult;
import com.qtsurfer.api.client.model.LiveSignal;
import com.qtsurfer.api.sdk.auth.AuthOptions;
import com.qtsurfer.api.sdk.auth.AuthenticatedClient;
import com.qtsurfer.api.sdk.errors.QTSError;
import io.github.centrifugal.centrifuge.internal.protocol.Protocol;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LiveConnectionTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MockWebServer api;
    private MockWebServer websocket;
    private final BlockingQueue<LiveSignal> signals = new LinkedBlockingQueue<>();
    private final BlockingQueue<Throwable> errors = new LinkedBlockingQueue<>();
    private final AtomicReference<String> connectionToken = new AtomicReference<>();
    private final AtomicReference<String> channel = new AtomicReference<>();
    private final AtomicReference<JsonNode> rpcBody = new AtomicReference<>();
    private final AtomicReference<WebSocket> firstSocket = new AtomicReference<>();
    private final AtomicReference<String> refreshedConnectionToken = new AtomicReference<>();
    private final AtomicReference<String> lastAuthorization = new AtomicReference<>();
    private final AtomicInteger tokenRequests = new AtomicInteger();
    private final AtomicInteger authRequests = new AtomicInteger();
    private final AtomicInteger socketConnections = new AtomicInteger();
    private boolean rejectSubscription;
    private boolean rejectRpc;
    private boolean refreshToken;
    private boolean retryUnauthorized;

    @BeforeEach
    void start() throws IOException {
        api = new MockWebServer();
        api.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
            @Override
            public MockResponse dispatch(okhttp3.mockwebserver.RecordedRequest request) {
                if ("/v1/auth/token".equals(request.getPath())) {
                    int requestNumber = authRequests.incrementAndGet();
                    return new MockResponse().setHeader("Content-Type", "application/json")
                            .setBody("{\"access_token\":\"jwt-" + requestNumber
                                    + "\",\"token_type\":\"Bearer\",\"expires_in\":3600,"
                                    + "\"scopes\":[],\"tier\":\"free\"}");
                }
                if ("/v1/live/token".equals(request.getPath())) {
                    int requestNumber = tokenRequests.incrementAndGet();
                    lastAuthorization.set(request.getHeader("Authorization"));
                    if (retryUnauthorized && requestNumber == 1) {
                        return new MockResponse().setResponseCode(401);
                    }
                    return new MockResponse().setHeader("Content-Type", "application/json")
                            .setBody("{\"token\":\"ws-token-" + requestNumber
                                    + "\",\"expiresAtMs\":9999999999999}");
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        api.start();

        websocket = new MockWebServer();
        websocket.enqueue(upgrade());
        websocket.enqueue(upgrade());
        websocket.start();
    }

    private MockResponse upgrade() {
        return new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            @Override
            public void onOpen(WebSocket socket, okhttp3.Response response) {
                if (socketConnections.incrementAndGet() == 1) firstSocket.set(socket);
            }

            @Override
            public void onMessage(WebSocket socket, okio.ByteString bytes) {
                try {
                    ByteArrayInputStream input = new ByteArrayInputStream(bytes.toByteArray());
                    while (input.available() > 0) {
                        handle(socket, Protocol.Command.parseDelimitedFrom(input));
                    }
                } catch (Exception error) {
                    errors.add(error);
                }
            }
        });
    }

    @AfterEach
    void stop() throws IOException {
        websocket.shutdown();
        api.shutdown();
    }

    @Test
    void connectsReceivesTypedSignalsAndUpdatesParameters() throws Exception {
        QTSurfer qts = client();
        try (LiveConnection connection = qts.connectLive("run-1", options()).get(5, TimeUnit.SECONDS)) {
            assertEquals("ws-token-1", connectionToken.get());
            assertEquals("sig:run-1", channel.get());

            LiveSignal signal = signals.poll(5, TimeUnit.SECONDS);
            assertNotNull(signal);
            assertEquals("signal-1", signal.getSignalId());
            assertEquals("run-1", signal.getRunId());

            LiveParamsUpdateResult result = connection.updateParams(
                    UpdateLiveParamsRequestBuilder.builder().param("emaFastPeriod", "12"))
                    .get(5, TimeUnit.SECONDS);
            assertEquals("run-1", result.getRunId());
            assertEquals(2, result.getParamsVersion());
            assertEquals("12", rpcBody.get().path("params").path("emaFastPeriod").asText());
            assertEquals("run-1", rpcBody.get().path("runId").asText());
        }
        assertEquals(1, tokenRequests.get());
    }

    @Test
    void refreshesConnectionTokenAndSurfacesRpcErrors() throws Exception {
        refreshToken = true;
        rejectRpc = true;
        try (LiveConnection connection = client().connectLive("run-1", options())
                .get(5, TimeUnit.SECONDS)) {
            CompletionException error = assertThrows(CompletionException.class,
                    () -> connection.updateParams(Map.of("emaFastPeriod", 12)).join());
            assertInstanceOf(QTSError.class, error.getCause());
            assertEquals("live.params failed: 409 stale parameters", error.getCause().getMessage());

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (refreshedConnectionToken.get() == null && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertEquals(2, tokenRequests.get());
            assertEquals("ws-token-2", refreshedConnectionToken.get());
        }
    }

    @Test
    void authenticatedSessionRefreshesJwtBeforeMintingWebSocketToken() throws Exception {
        retryUnauthorized = true;
        AuthenticatedClient authenticated = QTSurfer.authenticate("apikey",
                AuthOptions.builder().baseUrl(api.url("/v1").toString()).build());
        try (LiveConnection connection = authenticated.connectLive("run-1", options())
                .get(5, TimeUnit.SECONDS)) {
            assertNotNull(connection);
            assertEquals("ws-token-2", connectionToken.get());
            assertEquals("Bearer jwt-2", lastAuthorization.get());
        }
        assertEquals(2, authRequests.get());
        assertEquals(2, tokenRequests.get());
    }

    @Test
    void reconnectsAndResubscribesAfterTransportCloses() throws Exception {
        try (LiveConnection connection = client().connectLive("run-1", options())
                .get(5, TimeUnit.SECONDS)) {
            assertNotNull(signals.poll(5, TimeUnit.SECONDS));
            firstSocket.get().close(1001, "restart");
            assertNotNull(signals.poll(5, TimeUnit.SECONDS));
            assertEquals(2, socketConnections.get());
            assertEquals("sig:run-1", channel.get());
        }
    }

    @Test
    void rejectedSubscriptionFailsInitialConnection() {
        rejectSubscription = true;
        CompletionException error = assertThrows(CompletionException.class,
                () -> client().connectLive("run-1", options()).join());
        assertInstanceOf(QTSError.class, error.getCause());
        assertEquals("Live subscription rejected: 103 permission denied", error.getCause().getMessage());
    }

    private QTSurfer client() {
        return QTSurfer.builder().baseUrl(api.url("/v1").toString()).token("jwt").build();
    }

    private LiveConnectionOptions options() {
        return LiveConnectionOptions.builder(signals::add)
                .url(websocket.url("/connection/websocket").toString().replaceFirst("^http", "ws"))
                .connectTimeout(Duration.ofSeconds(4))
                .onError(errors::add)
                .build();
    }

    private void handle(WebSocket socket, Protocol.Command command) throws Exception {
        if (command.hasConnect()) {
            connectionToken.set(command.getConnect().getToken());
            send(socket, Protocol.Reply.newBuilder().setId(command.getId())
                    .setConnect(Protocol.ConnectResult.newBuilder().setClient("client-1")
                            .setPing(25).setPong(true).setExpires(refreshToken)
                            .setTtl(refreshToken ? 1 : 0)).build());
        } else if (command.hasSubscribe()) {
            channel.set(command.getSubscribe().getChannel());
            if (rejectSubscription) {
                send(socket, Protocol.Reply.newBuilder().setId(command.getId())
                        .setError(Protocol.Error.newBuilder().setCode(103)
                                .setMessage("permission denied")).build());
            } else {
                send(socket, Protocol.Reply.newBuilder().setId(command.getId())
                        .setSubscribe(Protocol.SubscribeResult.newBuilder()).build());
                byte[] signal = ("{\"v\":1,\"signalId\":\"signal-1\",\"runId\":\"run-1\","
                        + "\"stage\":\"live\",\"type\":\"info\",\"eventTsMs\":123,"
                        + "\"emittedAtMs\":124,\"digest\":\"hash\"}")
                        .getBytes(StandardCharsets.UTF_8);
                send(socket, Protocol.Reply.newBuilder().setPush(Protocol.Push.newBuilder()
                        .setChannel("sig:run-1")
                        .setPub(Protocol.Publication.newBuilder().setData(ByteString.copyFrom(signal))))
                        .build());
            }
        } else if (command.hasRpc()) {
            rpcBody.set(MAPPER.readTree(command.getRpc().getData().toByteArray()));
            if (rejectRpc) {
                send(socket, Protocol.Reply.newBuilder().setId(command.getId())
                        .setError(Protocol.Error.newBuilder().setCode(409)
                                .setMessage("stale parameters")).build());
            } else {
                send(socket, Protocol.Reply.newBuilder().setId(command.getId())
                        .setRpc(Protocol.RPCResult.newBuilder().setData(ByteString.copyFromUtf8(
                                "{\"runId\":\"run-1\",\"paramsVersion\":2,\"effectiveAtMs\":123}")))
                        .build());
            }
        } else if (command.hasRefresh()) {
            refreshedConnectionToken.set(command.getRefresh().getToken());
            send(socket, Protocol.Reply.newBuilder().setId(command.getId())
                    .setRefresh(Protocol.RefreshResult.newBuilder().setExpires(true).setTtl(600))
                    .build());
        }
    }

    private static void send(WebSocket socket, Protocol.Reply reply) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        reply.writeDelimitedTo(output);
        socket.send(okio.ByteString.of(output.toByteArray()));
    }
}
