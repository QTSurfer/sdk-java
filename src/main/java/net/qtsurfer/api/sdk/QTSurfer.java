package net.qtsurfer.api.sdk;

import net.qtsurfer.api.client.api.BacktestingApi;
import net.qtsurfer.api.client.binary.ExchangeBinaryDownloads;
import net.qtsurfer.api.client.invoker.ApiClient;
import net.qtsurfer.api.client.invoker.ApiException;
import net.qtsurfer.api.client.model.ResultMap;
import net.qtsurfer.api.sdk.errors.QTSDownloadError;
import net.qtsurfer.api.sdk.internal.HttpStrategyCompileClient;
import net.qtsurfer.api.sdk.workflows.BacktestWorkflow;

import java.io.InputStream;
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
 *     .baseUrl("https://api.qtsurfer.com/v1")
 *     .token(System.getenv("JWT_API_TOKEN"))
 *     .build();
 *
 * // One-shot shortcut:
 * ResultMap result = qts.backtest(request, options).join();
 *
 * // Or decomposed for streaming / reuse:
 * Strategy strategy = qts.compile(source).join();
 * Backtest job = strategy.backtest(request, options).join();
 * job.progress().subscribe( ... );
 * ResultMap result = job.await().join();
 * }</pre>
 */
public final class QTSurfer {

    private final QTSurferOptions options;
    private final BacktestWorkflow backtestWorkflow;
    private final ExchangeBinaryDownloads downloads;

    private QTSurfer(QTSurferOptions options, BacktestWorkflow backtestWorkflow, ExchangeBinaryDownloads downloads) {
        this.options = options;
        this.backtestWorkflow = backtestWorkflow;
        this.downloads = downloads;
    }

    public QTSurferOptions options() { return options; }

    /** Compile a strategy source. Resolves with a {@link Strategy} handle you can reuse. */
    public CompletableFuture<Strategy> compile(String source) {
        return compile(source, BacktestOptions.defaults());
    }

    public CompletableFuture<Strategy> compile(String source, BacktestOptions options) {
        Objects.requireNonNull(source, "source");
        return backtestWorkflow.compile(source, options);
    }

    /** Convenience: compile the strategy embedded in the given request. */
    public CompletableFuture<Strategy> compile(BacktestRequest request) {
        Objects.requireNonNull(request, "request");
        return compile(request.strategy(), BacktestOptions.defaults());
    }

    public CompletableFuture<Strategy> compile(BacktestRequest request, BacktestOptions options) {
        Objects.requireNonNull(request, "request");
        return compile(request.strategy(), options);
    }

    /**
     * Run the full compile → prepare → execute → await pipeline as a single future.
     * Equivalent to
     * {@code compile(request).thenCompose(s -> s.backtest(request, options)).thenCompose(Backtest::await)}.
     */
    public CompletableFuture<ResultMap> backtest(BacktestRequest request) {
        return backtest(request, BacktestOptions.defaults());
    }

    public CompletableFuture<ResultMap> backtest(BacktestRequest request, BacktestOptions options) {
        Objects.requireNonNull(request, "request");
        return backtestWorkflow.runFull(request, options);
    }

    /**
     * Download one hour of raw tickers for an instrument as a streaming
     * {@link InputStream}. Defaults to {@link DownloadFormat#LASTRA}; pass
     * {@link DownloadFormat#PARQUET} for on-the-fly Parquet conversion.
     *
     * <p>The caller is responsible for closing the stream — typically via
     * try-with-resources, piping to {@code Files.copy(...)}, or feeding it
     * into a Lastra/Parquet reader.
     *
     * @throws QTSDownloadError on HTTP 4xx/5xx or transport failure
     */
    public InputStream tickers(String exchangeId, String base, String quote, String hour) {
        return tickers(exchangeId, base, quote, hour, DownloadFormat.LASTRA);
    }

    public InputStream tickers(String exchangeId, String base, String quote, String hour, DownloadFormat format) {
        Objects.requireNonNull(format, "format");
        try {
            return downloads.getTickersHour(exchangeId, base, quote, hour, format.wire());
        } catch (ApiException e) {
            throw new QTSDownloadError(
                    "tickers download failed: " + describe(e), e);
        }
    }

    /**
     * Download one hour of klines for an instrument as a streaming
     * {@link InputStream}. See {@link #tickers} for semantics.
     *
     * @throws QTSDownloadError on HTTP 4xx/5xx or transport failure
     */
    public InputStream klines(String exchangeId, String base, String quote, String hour) {
        return klines(exchangeId, base, quote, hour, DownloadFormat.LASTRA);
    }

    public InputStream klines(String exchangeId, String base, String quote, String hour, DownloadFormat format) {
        Objects.requireNonNull(format, "format");
        try {
            return downloads.getKlinesHour(exchangeId, base, quote, hour, format.wire());
        } catch (ApiException e) {
            throw new QTSDownloadError(
                    "klines download failed: " + describe(e), e);
        }
    }

    private static String describe(ApiException e) {
        if (e.getResponseBody() != null && !e.getResponseBody().isBlank()) {
            return "HTTP " + e.getCode() + " — " + e.getResponseBody();
        }
        return "HTTP " + e.getCode();
    }

    public static Builder builder() { return new Builder(); }

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
            BacktestingApi backtestingApi = new BacktestingApi(apiClient);
            ExecutorService exec = opts.executor() != null ? opts.executor() : ForkJoinPool.commonPool();
            BacktestWorkflow workflow = new BacktestWorkflow(
                    new HttpStrategyCompileClient(apiClient), backtestingApi, exec);
            return new QTSurfer(opts, workflow, new ExchangeBinaryDownloads(apiClient));
        }
    }
}
