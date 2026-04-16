package net.qtsurfer.api.sdk;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

/**
 * Configuration for a {@link QTSurfer} client.
 *
 * @param baseUrl    API base URL (e.g. {@code https://api.qtsurfer.com/v1})
 * @param token      bearer token; {@code null} disables the {@code Authorization} header
 * @param httpClient optional custom {@link HttpClient}; when {@code null} the SDK creates one
 * @param executor   executor that runs the async workflow; when {@code null} uses {@code ForkJoinPool.commonPool()}
 */
public record QTSurferOptions(
        URI baseUrl,
        String token,
        HttpClient httpClient,
        ExecutorService executor
) {
    public QTSurferOptions {
        Objects.requireNonNull(baseUrl, "baseUrl");
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private URI baseUrl;
        private String token;
        private HttpClient httpClient;
        private ExecutorService executor;

        public Builder baseUrl(URI baseUrl) { this.baseUrl = baseUrl; return this; }
        public Builder baseUrl(String baseUrl) { this.baseUrl = URI.create(baseUrl); return this; }
        public Builder token(String token) { this.token = token; return this; }
        public Builder httpClient(HttpClient httpClient) { this.httpClient = httpClient; return this; }
        public Builder executor(ExecutorService executor) { this.executor = executor; return this; }

        public QTSurferOptions build() {
            return new QTSurferOptions(baseUrl, token, httpClient, executor);
        }
    }
}
