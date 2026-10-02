package com.qtsurfer.api.sdk;

import com.qtsurfer.api.client.model.LiveSignal;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

/** Configuration and callbacks for one live-run WebSocket subscription. */
public final class LiveConnectionOptions {
    public static final URI DEFAULT_URL = URI.create("wss://rt.qtsurfer.net/connection/websocket");

    private final URI url;
    private final Duration connectTimeout;
    private final Consumer<LiveSignal> onSignal;
    private final Consumer<Throwable> onError;

    private LiveConnectionOptions(Builder builder) {
        url = builder.url;
        connectTimeout = builder.connectTimeout;
        onSignal = builder.onSignal;
        onError = builder.onError;
    }

    public URI url() { return url; }
    public Duration connectTimeout() { return connectTimeout; }
    public Consumer<LiveSignal> onSignal() { return onSignal; }
    public Consumer<Throwable> onError() { return onError; }

    /** Start configuring a subscription; the signal callback is required. */
    public static Builder builder(Consumer<LiveSignal> onSignal) {
        return new Builder(onSignal);
    }

    public static final class Builder {
        private URI url = DEFAULT_URL;
        private Duration connectTimeout = Duration.ofSeconds(20);
        private final Consumer<LiveSignal> onSignal;
        private Consumer<Throwable> onError;

        private Builder(Consumer<LiveSignal> onSignal) {
            this.onSignal = Objects.requireNonNull(onSignal, "onSignal");
        }

        /** Override the staging WebSocket endpoint. */
        public Builder url(URI url) {
            this.url = Objects.requireNonNull(url, "url");
            return this;
        }

        public Builder url(String url) { return url(URI.create(url)); }

        /** Limit the initial connect-and-subscribe wait. */
        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout");
            return this;
        }

        /** Receive transport, subscription, and signal-decoding errors after connection. */
        public Builder onError(Consumer<Throwable> onError) {
            this.onError = Objects.requireNonNull(onError, "onError");
            return this;
        }

        public LiveConnectionOptions build() {
            String scheme = url.getScheme();
            if (!"ws".equals(scheme) && !"wss".equals(scheme)) {
                throw new IllegalArgumentException("Live connection URL must use ws or wss");
            }
            if (connectTimeout.isNegative() || connectTimeout.isZero()) {
                throw new IllegalArgumentException("connectTimeout must be positive");
            }
            return new LiveConnectionOptions(this);
        }
    }
}
