package com.qtsurfer.api.sdk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qtsurfer.api.client.model.LiveParamsUpdateResult;
import com.qtsurfer.api.client.model.LiveSignal;
import com.qtsurfer.api.client.model.UpdateLiveParamsRequest;
import com.qtsurfer.api.sdk.errors.QTSError;
import io.github.centrifugal.centrifuge.Client;
import io.github.centrifugal.centrifuge.ConnectionTokenEvent;
import io.github.centrifugal.centrifuge.ConnectionTokenGetter;
import io.github.centrifugal.centrifuge.DisconnectedEvent;
import io.github.centrifugal.centrifuge.ErrorEvent;
import io.github.centrifugal.centrifuge.EventListener;
import io.github.centrifugal.centrifuge.HistoryOptions;
import io.github.centrifugal.centrifuge.HistoryResult;
import io.github.centrifugal.centrifuge.Options;
import io.github.centrifugal.centrifuge.Publication;
import io.github.centrifugal.centrifuge.PublicationEvent;
import io.github.centrifugal.centrifuge.RPCResult;
import io.github.centrifugal.centrifuge.ReplyError;
import io.github.centrifugal.centrifuge.SubscribedEvent;
import io.github.centrifugal.centrifuge.Subscription;
import io.github.centrifugal.centrifuge.SubscriptionErrorEvent;
import io.github.centrifugal.centrifuge.SubscriptionEventListener;
import io.github.centrifugal.centrifuge.StreamPosition;
import io.github.centrifugal.centrifuge.TokenCallback;
import io.github.centrifugal.centrifuge.UnsubscribedEvent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * A managed WebSocket subscription to one live run's signal channel.
 *
 * <p>The connection owns its Centrifugo client and must be closed when no longer needed.
 * Reconnects and token refresh are managed automatically. A reconnect may miss signals;
 * use {@link #history(int, LiveSignalHistory.Position)} for the channel's recent sandbox
 * signals, or {@code getLiveSignals} for durable retained history.
 */
public final class LiveConnection implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String runId;
    private final Client client;
    private Subscription subscription;
    private final LiveConnectionOptions options;
    private final CompletableFuture<LiveConnection> ready = new CompletableFuture<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    private LiveConnection(String runId, Client client, LiveConnectionOptions options) {
        this.runId = runId;
        this.client = client;
        this.options = options;
    }

    /** Mint a token, connect, and resolve after the signal channel is subscribed. */
    public static CompletableFuture<LiveConnection> connect(
            String runId, LiveConnectionOptions options, Supplier<String> tokenProvider) {
        Objects.requireNonNull(runId, "runId");
        if (runId.isBlank()) throw new IllegalArgumentException("runId must not be blank");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(tokenProvider, "tokenProvider");

        return CompletableFuture.supplyAsync(tokenProvider).thenCompose(token -> {
            Options clientOptions = new Options();
            clientOptions.setToken(token);
            clientOptions.setTokenGetter(new ConnectionTokenGetter() {
                @Override
                public void getConnectionToken(ConnectionTokenEvent event, TokenCallback callback) {
                    CompletableFuture.supplyAsync(tokenProvider).whenComplete((fresh, error) ->
                            callback.Done(error == null ? null : unwrap(error), fresh));
                }
            });

            AtomicReference<LiveConnection> reference = new AtomicReference<>();
            Client client = new Client(options.url().toString(), clientOptions, new EventListener() {
                @Override
                public void onError(Client source, ErrorEvent event) {
                    reference.get().report(event.getError());
                }

                @Override
                public void onDisconnected(Client source, DisconnectedEvent event) {
                    LiveConnection connection = reference.get();
                    if (!connection.closed.get()) {
                        QTSError error = new QTSError("Live connection closed: " + event.getReason());
                        connection.ready.completeExceptionally(error);
                        connection.report(error);
                    }
                }
            });
            LiveConnection connection = new LiveConnection(runId, client, options);
            reference.set(connection);
            try {
                Subscription subscription = client.newSubscription("sig:" + runId,
                        new SubscriptionEventListener() {
                    @Override
                    public void onSubscribed(Subscription source, SubscribedEvent event) {
                        connection.ready.complete(connection);
                    }

                    @Override
                    public void onPublication(Subscription source, PublicationEvent event) {
                        connection.deliver(event.getData());
                    }

                    @Override
                    public void onError(Subscription source, SubscriptionErrorEvent event) {
                        connection.report(event.getError());
                    }

                    @Override
                    public void onUnsubscribed(Subscription source, UnsubscribedEvent event) {
                        if (!connection.closed.get()) {
                            QTSError error = new QTSError("Live subscription rejected: "
                                    + event.getCode() + " " + event.getReason());
                            connection.ready.completeExceptionally(error);
                            connection.report(error);
                        }
                    }
                });
                connection.subscription = subscription;
                subscription.subscribe();
                client.connect();
            } catch (Exception error) {
                connection.ready.completeExceptionally(error);
            }
            connection.ready.orTimeout(options.connectTimeout().toMillis(), TimeUnit.MILLISECONDS)
                    .whenComplete((value, error) -> {
                        if (error != null) connection.close();
                    });
            return connection.ready;
        });
    }

    /** Send the same owner-only parameter update as REST {@code PUT /live/{runId}/params}. */
    public CompletableFuture<LiveParamsUpdateResult> updateParams(UpdateLiveParamsRequest request) {
        Objects.requireNonNull(request, "request");
        if (closed.get()) return CompletableFuture.failedFuture(new QTSError("Live connection is closed"));
        if (request.getParams() == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("params must not be null"));
        }

        byte[] body;
        try {
            body = MAPPER.writeValueAsBytes(Map.of("runId", runId, "params", request.getParams()));
        } catch (JsonProcessingException error) {
            return CompletableFuture.failedFuture(new QTSError("Invalid live parameters", error));
        }
        CompletableFuture<LiveParamsUpdateResult> result = new CompletableFuture<>();
        client.rpc("live.params", body, (error, response) -> {
            if (error != null) {
                result.completeExceptionally(rpcError(error));
                return;
            }
            try {
                RPCResult rpc = Objects.requireNonNull(response, "Empty live.params response");
                if (rpc.getError() != null) {
                    result.completeExceptionally(rpcError(rpc.getError()));
                } else {
                    result.complete(MAPPER.readValue(rpc.getData(), LiveParamsUpdateResult.class));
                }
            } catch (Exception parseError) {
                result.completeExceptionally(new QTSError("Invalid live.params response", parseError));
            }
        });
        return result;
    }

    /** Update parameters using the SDK's fluent request builder. */
    public CompletableFuture<LiveParamsUpdateResult> updateParams(UpdateLiveParamsRequestBuilder request) {
        return updateParams(Objects.requireNonNull(request, "request").build());
    }

    /** Update parameters using strategy property names and values. */
    public CompletableFuture<LiveParamsUpdateResult> updateParams(Map<String, ?> params) {
        return updateParams(UpdateLiveParamsRequestBuilder.builder().params(params));
    }

    /** Read up to 300 recent sandbox signals from the subscribed channel, oldest first. */
    public CompletableFuture<LiveSignalHistory> history() {
        return history(300, null);
    }

    /**
     * Read the channel's recent sandbox signals after an optional earlier position.
     *
     * <p>A {@code null} position starts at the oldest held signal. A limit of zero reads only
     * the current position. Error 112 means the position's epoch is no longer available;
     * retry without {@code since} or use the REST retained-signals endpoint.
     */
    public CompletableFuture<LiveSignalHistory> history(int limit, LiveSignalHistory.Position since) {
        if (limit < 0) throw new IllegalArgumentException("limit must not be negative");
        if (closed.get()) return CompletableFuture.failedFuture(new QTSError("Live connection is closed"));

        HistoryOptions.Builder request = new HistoryOptions.Builder().withLimit(limit);
        if (since != null) request.withSince(new StreamPosition(since.offset(), since.epoch()));
        CompletableFuture<LiveSignalHistory> result = new CompletableFuture<>();
        subscription.history(request.build(), (error, response) -> {
            if (error != null) {
                result.completeExceptionally(rpcError("history", error));
                return;
            }
            try {
                result.complete(decodeHistory(Objects.requireNonNull(response, "Empty history response")));
            } catch (Exception parseError) {
                result.completeExceptionally(new QTSError("Invalid history response", parseError));
            }
        });
        return result;
    }

    private static LiveSignalHistory decodeHistory(HistoryResult response) throws IOException {
        List<LiveSignalHistory.Publication> publications = new ArrayList<>();
        for (Publication publication : response.getPublications()) {
            publications.add(new LiveSignalHistory.Publication(
                    MAPPER.readValue(publication.getData(), LiveSignal.class), publication.getOffset()));
        }
        return new LiveSignalHistory(publications, response.getEpoch(), response.getOffset());
    }

    private void deliver(byte[] data) {
        if (closed.get()) return;
        LiveSignal signal;
        try {
            signal = MAPPER.readValue(data, LiveSignal.class);
        } catch (IOException error) {
            report(new QTSError("Invalid live signal", error));
            return;
        }
        try {
            options.onSignal().accept(signal);
        } catch (RuntimeException error) {
            report(error);
        }
    }

    private void report(Throwable error) {
        if (closed.get() || options.onError() == null) return;
        try {
            options.onError().accept(error);
        } catch (RuntimeException ignored) {
            close();
        }
    }

    private static Throwable unwrap(Throwable error) {
        return error instanceof CompletionException && error.getCause() != null
                ? error.getCause() : error;
    }

    private static QTSError rpcError(Throwable error) {
        if (error instanceof ReplyError reply) {
            return new QTSError("live.params failed: " + reply.getCode() + " " + reply.getMessage(), reply);
        }
        return new QTSError("live.params failed: " + error.getMessage(), error);
    }

    private static QTSError rpcError(String operation, Throwable error) {
        if (error instanceof ReplyError reply) {
            return new QTSError(operation + " failed: " + reply.getCode() + " " + reply.getMessage(), reply);
        }
        return new QTSError(operation + " failed: " + error.getMessage(), error);
    }

    /** Stop the subscription and reconnect loop; equivalent to {@link #close()}. */
    public void disconnect() {
        close();
    }

    /** Stop reconnecting and release the underlying WebSocket client and threads. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        ready.completeExceptionally(new QTSError("Live connection closed"));
        try {
            client.close(0);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }
}
