package com.qtsurfer.api.sdk.auth;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

/**
 * Configuration for {@link AuthenticatedClient}. Mirrors
 * {@code QTSurferOptions} but replaces the static {@code token} with a
 * pluggable {@link TokenStore}.
 *
 * @param baseUrl    API base URL. Defaults to {@code https://api.qtsurfer.com/v1}.
 * @param store      pluggable token store; defaults to {@link InMemoryTokenStore}.
 * @param httpClient optional custom {@link HttpClient}.
 * @param executor   executor that runs the async workflow.
 */
public record AuthOptions(
        URI baseUrl,
        TokenStore store,
        HttpClient httpClient,
        ExecutorService executor
) {
    public static final URI DEFAULT_BASE_URL = URI.create("https://api.qtsurfer.com/v1");

    public AuthOptions {
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(store, "store");
    }

    public static AuthOptions defaults() {
        return builder().build();
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private URI baseUrl = DEFAULT_BASE_URL;
        private TokenStore store = new InMemoryTokenStore();
        private HttpClient httpClient;
        private ExecutorService executor;

        public Builder baseUrl(URI baseUrl) { this.baseUrl = baseUrl; return this; }
        public Builder baseUrl(String baseUrl) { this.baseUrl = URI.create(baseUrl); return this; }
        public Builder store(TokenStore store) { this.store = store; return this; }
        public Builder httpClient(HttpClient httpClient) { this.httpClient = httpClient; return this; }
        public Builder executor(ExecutorService executor) { this.executor = executor; return this; }

        public AuthOptions build() {
            return new AuthOptions(baseUrl, store, httpClient, executor);
        }
    }
}
