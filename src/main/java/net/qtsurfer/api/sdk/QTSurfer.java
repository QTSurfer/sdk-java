package net.qtsurfer.api.sdk;

import net.qtsurfer.api.client.api.BacktestingApi;
import net.qtsurfer.api.client.invoker.ApiClient;
import net.qtsurfer.api.client.model.ResultMap;
import net.qtsurfer.api.sdk.internal.HttpStrategyCompileClient;
import net.qtsurfer.api.sdk.workflows.Backtest;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;

/**
 * High-level SDK for the QTSurfer platform.
 *
 * <h2>Quick start</h2>
 * <pre>{@code
 * QTSurfer qts = QTSurfer.builder()
 *     .baseUrl("https://api.qtsurfer.net/v1")
 *     .token(System.getenv("JWT_API_TOKEN"))
 *     .build();
 *
 * CompletableFuture<ResultMap> future = qts.backtest(
 *     BacktestRequest.builder()
 *         .strategy(Files.readString(Path.of("Strategy.java")))
 *         .exchangeId("binance")
 *         .instrument("BTC/USDT")
 *         .from("2026-04-13T00:00:00Z")
 *         .to("2026-04-14T00:00:00Z")
 *         .build(),
 *     BacktestOptions.builder()
 *         .onProgress(p -> System.out.println(p.stage() + " " + p.percent()))
 *         .timeout(Duration.ofMinutes(10))
 *         .build());
 *
 * ResultMap result = future.join();
 * }</pre>
 */
public final class QTSurfer {

    private final QTSurferOptions options;
    private final Backtest backtestWorkflow;

    private QTSurfer(QTSurferOptions options, Backtest backtestWorkflow) {
        this.options = options;
        this.backtestWorkflow = backtestWorkflow;
    }

    public QTSurferOptions options() { return options; }

    /**
     * Run a backtest (compile → prepare → execute) returning a {@link CompletableFuture} that
     * resolves with the result {@link ResultMap} when the whole workflow completes.
     *
     * <p>Cancel the returned future to stop polling and trigger a best-effort server-side
     * {@code cancelExecution} if the workflow already reached the execute stage.
     */
    public CompletableFuture<ResultMap> backtest(BacktestRequest request) {
        return backtest(request, BacktestOptions.defaults());
    }

    public CompletableFuture<ResultMap> backtest(BacktestRequest request, BacktestOptions options) {
        Objects.requireNonNull(request, "request");
        return backtestWorkflow.run(request, options);
    }

    public static Builder builder() { return new Builder(); }

    /** Fluent builder mirroring {@link QTSurferOptions}. */
    public static final class Builder {
        private final QTSurferOptions.Builder delegate = QTSurferOptions.builder();

        public Builder baseUrl(String baseUrl) { delegate.baseUrl(baseUrl); return this; }
        public Builder baseUrl(java.net.URI baseUrl) { delegate.baseUrl(baseUrl); return this; }
        public Builder token(String token) { delegate.token(token); return this; }
        public Builder httpClient(java.net.http.HttpClient httpClient) { delegate.httpClient(httpClient); return this; }
        public Builder executor(ExecutorService executor) { delegate.executor(executor); return this; }

        public QTSurfer build() {
            QTSurferOptions opts = delegate.build();
            ApiClient apiClient = new ApiClient();
            apiClient.updateBaseUri(opts.baseUrl().toString());
            if (opts.token() != null) {
                String bearer = "Bearer " + opts.token();
                apiClient.setRequestInterceptor(b -> b.header("Authorization", bearer));
            }
            if (opts.httpClient() != null) {
                apiClient.setHttpClientBuilder(java.net.http.HttpClient.newBuilder()
                        .connectTimeout(java.time.Duration.ofSeconds(30))); // fallback; api-client requires a builder
            }
            BacktestingApi backtestingApi = new BacktestingApi(apiClient);
            ExecutorService exec = opts.executor() != null ? opts.executor() : ForkJoinPool.commonPool();
            return new QTSurfer(opts, new Backtest(
                    new HttpStrategyCompileClient(apiClient), backtestingApi, exec));
        }
    }
}
