package com.qtsurfer.api.sdk;

import com.qtsurfer.api.client.api.BacktestingApi;
import com.qtsurfer.api.client.api.ExchangeApi;
import com.qtsurfer.api.client.api.StrategyApi;
import com.qtsurfer.api.client.binary.ExchangeBinaryDownloads;
import com.qtsurfer.api.client.invoker.ApiClient;
import com.qtsurfer.api.client.invoker.ApiException;
import com.qtsurfer.api.client.model.Exchange;
import com.qtsurfer.api.client.model.InstrumentDetail;
import com.qtsurfer.api.client.model.ResultMap;
import com.qtsurfer.api.client.model.StrategyState;
import com.qtsurfer.api.sdk.auth.AuthOptions;
import com.qtsurfer.api.sdk.auth.AuthenticatedClient;
import com.qtsurfer.api.sdk.errors.QTSDownloadError;
import com.qtsurfer.api.sdk.errors.QTSError;
import com.qtsurfer.api.sdk.internal.HttpStrategyCompileClient;
import com.qtsurfer.api.sdk.internal.ValidationOutcomes;
import com.qtsurfer.api.sdk.workflows.BacktestWorkflow;
import com.qtsurfer.api.sdk.workflows.SweepWorkflow;

import java.io.InputStream;
import java.util.List;
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
 *
 * // Or sweep a parameter grid instead of running one configuration:
 * Sweep sweep = qts.sweep(sweepRequest).join();
 * ExecuteSweepResult leaderboard = sweep.await().join();
 * }</pre>
 */
public final class QTSurfer {

    private final QTSurferOptions options;
    private final BacktestWorkflow backtestWorkflow;
    private final SweepWorkflow sweepWorkflow;
    private final ExchangeBinaryDownloads downloads;
    private final ExchangeApi exchangeApi;
    private final StrategyApi strategyApi;

    private QTSurfer(QTSurferOptions options, BacktestWorkflow backtestWorkflow,
                     SweepWorkflow sweepWorkflow, ExchangeBinaryDownloads downloads,
                     ExchangeApi exchangeApi, StrategyApi strategyApi) {
        this.options = options;
        this.backtestWorkflow = backtestWorkflow;
        this.sweepWorkflow = sweepWorkflow;
        this.downloads = downloads;
        this.exchangeApi = exchangeApi;
        this.strategyApi = strategyApi;
    }

    /** Configuration this client was built with. */
    public QTSurferOptions options() { return options; }

    /** Compile a strategy source. Resolves with a {@link Strategy} handle you can reuse. */
    public CompletableFuture<Strategy> compile(String source) {
        return compile(source, BacktestOptions.defaults());
    }

    /**
     * Compile strategy source into a reusable {@link Strategy} handle.
     *
     * <p>Issues a single synchronous HTTP request; the compile endpoint
     * returns the {@code strategyId} directly, so a failing compile
     * surfaces immediately as
     * {@link com.qtsurfer.api.sdk.errors.QTSStrategyCompileError} rather
     * than on a later poll. A {@code 429} means the platform was holding
     * too many compilations and the source was never judged, so it is safe
     * to retry; any other error status reflects a judgment on the
     * submitted code and will not succeed by retrying alone.
     *
     * @param options tuning knobs; only {@code onProgress} is used here (a
     *                single {@code COMPILING} event) — polling and timeout
     *                settings do not apply to this stage
     */
    public CompletableFuture<Strategy> compile(String source, BacktestOptions options) {
        Objects.requireNonNull(source, "source");
        return backtestWorkflow.compile(source, options);
    }

    /** Convenience: compile the strategy embedded in the given request. */
    public CompletableFuture<Strategy> compile(BacktestRequest request) {
        Objects.requireNonNull(request, "request");
        return compile(request.strategy(), BacktestOptions.defaults());
    }

    /**
     * Convenience overload that compiles {@link BacktestRequest#strategy()}
     * with the given options, ignoring every other field of the request.
     * See {@link #compile(String, BacktestOptions)}.
     */
    public CompletableFuture<Strategy> compile(BacktestRequest request, BacktestOptions options) {
        Objects.requireNonNull(request, "request");
        return compile(request.strategy(), options);
    }

    /**
     * Ask the platform to check that a registered strategy can actually run.
     * The compiled class is instantiated and driven through a bounded
     * synthetic series, so a wiring fault surfaces here instead of at the
     * first backtest. Synchronous — blocks the calling thread for the HTTP
     * round trip.
     *
     * <p><strong>Did this call start work, and is there a verdict? Two
     * questions, two answers.</strong> The call is idempotent: it either
     * queues a check, or queues nothing because the current compilation is
     * already accounted for. The returned {@link ValidationOutcome} says
     * which — {@link ValidationOutcome.Queued} for the first,
     * {@link ValidationOutcome.NotQueued} for the second.
     *
     * <p><strong>{@code NotQueued} does not mean a verdict exists.</strong>
     * The {@link StrategyState} it carries can itself be {@code pending} — a
     * check queued by an earlier call, possibly from another process, that
     * has not answered yet. So a caller that wants a verdict has to read one
     * either way:
     *
     * <ul>
     *   <li>{@link ValidationOutcome#queued()} — did <em>this</em> call start
     *       a check?</li>
     *   <li>{@link StrategyState#getValidation()} — is there a verdict right
     *       now? {@code passed} or {@code failed} is terminal;
     *       {@code pending} is not, so poll {@link #strategyState(String)}
     *       until it leaves {@code pending}.</li>
     * </ul>
     *
     * <p>Polling needs its own deadline. A queued check can go unreported for
     * far longer than one takes — the platform reports that as
     * {@link StrategyState#getValidationStalled()} — so {@code pending} is
     * not guaranteed to resolve, and a caller that waits without a timeout
     * can wait indefinitely. A stall disproves nothing about the strategy;
     * the check simply has not run. This SDK ships no polling helper.
     *
     * <p><strong>{@code passed} does not mean the strategy is correct.</strong>
     * It means the class loaded and survived the first event of a short
     * synthetic run — a floor, not a guarantee, and not a statement that the
     * strategy is safe to run. When
     * {@link StrategyState#getDryRunIncomplete()} is true the check did not
     * even finish its budget, so the floor is lower still and an empty
     * {@link StrategyState#getNotices()} list is not a clean bill of health.
     *
     * @param strategyId id of a registered strategy, as returned by
     *                   {@link #compile(String)}
     * @return whether this call queued a check, and — when it did not — the
     *         state the platform holds
     * @throws QTSError on HTTP 4xx/5xx (including {@code 404} when no such
     *                  strategy is registered for this caller) or transport
     *                  failure
     */
    public ValidationOutcome validateStrategy(String strategyId) {
        Objects.requireNonNull(strategyId, "strategyId");
        try {
            return ValidationOutcomes.of(
                    strategyId, strategyApi.validateStrategyWithHttpInfo(strategyId));
        } catch (ApiException e) {
            throw new QTSError("validateStrategy call failed: " + describe(e), e);
        }
    }

    /**
     * Fetch what the platform knows about a registered strategy: that it
     * compiled, what market data the compiled class needs, and what
     * validating it found. Synchronous — blocks the calling thread for the
     * HTTP round trip.
     *
     * <p>Resolves to the api-client's {@link StrategyState} record — the
     * platform's view of the strategy — not to the SDK's {@link Strategy}
     * handle that {@link #compile(String)} produces.
     *
     * <p>This is the endpoint to poll while a check is outstanding, whether
     * {@link #validateStrategy(String)} queued it or reported that one was
     * already accounted for. See that method for what a verdict does and does
     * not mean, and for why a polling loop needs its own deadline.
     *
     * <p>A verdict describes the bytecode that produced it, and recompiling
     * supersedes it: when {@link StrategyState#getCompiledAt()} is later than
     * {@link StrategyState#getValidatedAt()}, the recorded verdict was reached
     * against a compilation that is no longer what would run, and
     * {@link #validateStrategy(String)} can be called again to refresh it.
     *
     * <p>A {@code 404} means exactly one thing — no such registered strategy
     * for this caller. It is never a stale or expired answer; registration and
     * verdict are stored durably, not cached.
     *
     * @param strategyId id of a registered strategy, as returned by
     *                   {@link #compile(String)}
     * @return the platform's record of the strategy
     * @throws QTSError on HTTP 4xx/5xx or transport failure
     */
    public StrategyState strategyState(String strategyId) {
        Objects.requireNonNull(strategyId, "strategyId");
        try {
            return strategyApi.getStrategy(strategyId);
        } catch (ApiException e) {
            throw new QTSError("strategyState call failed: " + describe(e), e);
        }
    }

    /**
     * Run the full compile → prepare → execute → await pipeline as a single future.
     * Equivalent to
     * {@code compile(request).thenCompose(s -> s.backtest(request, options)).thenCompose(Backtest::await)}.
     */
    public CompletableFuture<ResultMap> backtest(BacktestRequest request) {
        return backtest(request, BacktestOptions.defaults());
    }

    /**
     * Run the full compile → prepare → execute pipeline and resolve once
     * the run reaches a terminal state (completed, failed, or canceled).
     * The returned future completes exceptionally with
     * {@link com.qtsurfer.api.sdk.errors.QTSStrategyCompileError} if
     * compilation fails, {@link com.qtsurfer.api.sdk.errors.QTSPreparationError}
     * if data preparation fails, {@link com.qtsurfer.api.sdk.errors.QTSExecutionError}
     * if execution fails, or {@link com.qtsurfer.api.sdk.errors.QTSTimeoutError}
     * if a stage exceeds its configured timeout.
     *
     * @param options tuning knobs (poll interval, timeout, progress
     *                callback) applied to every stage of the pipeline
     */
    public CompletableFuture<ResultMap> backtest(BacktestRequest request, BacktestOptions options) {
        Objects.requireNonNull(request, "request");
        return backtestWorkflow.runFull(request, options);
    }

    /**
     * Equivalent to {@link #sweep(SweepRequest, SweepOptions)} with
     * {@link SweepOptions#defaults()}.
     *
     * @param request the grid, the instrument, and the window
     * @return the handle, once the platform has accepted the sweep
     */
    public CompletableFuture<Sweep> sweep(SweepRequest request) {
        return sweep(request, SweepOptions.defaults());
    }

    /**
     * Run the full compile → prepare → executeSweep pipeline and resolve once
     * the platform has accepted the sweep, handing back a {@link Sweep} that
     * keeps polling the leaderboard in the background.
     *
     * <p>The whole sweep is one call because the execute-sweep endpoint is
     * addressed by the id of an already-prepared dataset: exposing the stages
     * separately would hand dataset lifecycle to the caller and buy nothing.
     * Preparing is idempotent, so sweeping the same window twice prepares it
     * once.
     *
     * <p>The returned future completes exceptionally with
     * {@link com.qtsurfer.api.sdk.errors.QTSStrategyCompileError} if
     * compilation fails,
     * {@link com.qtsurfer.api.sdk.errors.QTSPreparationError} if data
     * preparation fails,
     * {@link com.qtsurfer.api.sdk.errors.QTSExecutionError} if the platform
     * rejects the sweep — an expanded grid over the server limit, or a
     * walk-forward request whose fold count multiplies past the sweep budget,
     * both answer {@code 400} — or
     * {@link com.qtsurfer.api.sdk.errors.QTSTimeoutError} if a stage exceeds
     * its configured timeout.
     *
     * <p>What the sweep <em>found</em> arrives through {@link Sweep#await()},
     * which is also where the semantics of the leaderboard are documented.
     * Acceptance already answers three things worth reading before any result
     * exists — the effective seed, whether this submission enqueued anything,
     * and whether this is a walk-forward sweep — see {@link Sweep#accepted()}.
     *
     * @param request the grid, the instrument, and the window
     * @param options tuning knobs (poll interval, timeout, progress callback,
     *                leaderboard ordering) applied to every stage of the pipeline
     * @return the handle, once the platform has accepted the sweep
     */
    public CompletableFuture<Sweep> sweep(SweepRequest request, SweepOptions options) {
        Objects.requireNonNull(request, "request");
        return sweepWorkflow.submit(request, options);
    }

    /**
     * List available exchanges on the platform.
     *
     * @throws QTSError on HTTP 4xx/5xx or transport failure
     */
    public List<Exchange> exchanges() {
        try {
            return exchangeApi.listExchanges();
        } catch (ApiException e) {
            throw new QTSError("exchanges call failed: " + describe(e), e);
        }
    }

    /**
     * List instruments available on the given exchange, including per-data-type
     * coverage (see {@link InstrumentDetail#getCoverage()}) and market info.
     *
     * <p>Unwraps the {@code InstrumentListResponse} HAL envelope returned by the
     * underlying API client and returns just the instrument list.
     *
     * @param exchangeId exchange identifier (e.g. {@code "binance"})
     * @throws QTSError on HTTP 4xx/5xx or transport failure
     */
    public List<InstrumentDetail> instruments(String exchangeId) {
        Objects.requireNonNull(exchangeId, "exchangeId");
        try {
            return exchangeApi.listInstruments(exchangeId).getData();
        } catch (ApiException e) {
            throw new QTSError("instruments call failed: " + describe(e), e);
        }
    }

    /**
     * List the instruments of one market segment of the given exchange,
     * including per-data-type coverage (see
     * {@link InstrumentDetail#getCoverage()}) and market info.
     *
     * <p>Unwraps the same {@code InstrumentListResponse} HAL envelope as
     * {@link #instruments(String)} and returns just the instrument list. The
     * single-argument overload is the default-segment shortcut and lists the
     * {@code spot} segment.
     *
     * @param exchangeId exchange identifier (e.g. {@code "binance"})
     * @param segment    market segment to list: {@code "spot"} or
     *                   {@code "futures"}
     * @return the instruments of that segment
     * @throws QTSError on HTTP 4xx/5xx or transport failure
     */
    public List<InstrumentDetail> instruments(String exchangeId, String segment) {
        Objects.requireNonNull(exchangeId, "exchangeId");
        Objects.requireNonNull(segment, "segment");
        try {
            return exchangeApi.listSegmentInstruments(exchangeId, segment).getData();
        } catch (ApiException e) {
            throw new QTSError("instruments call failed: " + describe(e), e);
        }
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

    /**
     * Download one hour of raw tickers for an instrument as a streaming
     * {@link InputStream}, requesting the given {@link DownloadFormat}.
     * The 4-argument overload delegates here with
     * {@link DownloadFormat#LASTRA}.
     *
     * @throws QTSDownloadError on HTTP 4xx/5xx or transport failure
     */
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

    /**
     * Download one hour of klines for an instrument as a streaming
     * {@link InputStream}, requesting the given {@link DownloadFormat}.
     * See {@link #tickers(String, String, String, String, DownloadFormat)}
     * for stream-closing semantics.
     *
     * @throws QTSDownloadError on HTTP 4xx/5xx or transport failure
     */
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

    /**
     * One-call setup: exchange an API key for a short-lived JWT and return
     * an {@link AuthenticatedClient} that mirrors this SDK's surface
     * (compile / validateStrategy / strategyState / backtest / sweep /
     * exchanges / instruments / tickers / klines) with automatic
     * refresh-on-401.
     *
     * <p>If {@code apikey} is {@code null} or blank, the value is read from
     * the {@code QTSURFER_APIKEY} environment variable.
     *
     * @throws com.qtsurfer.api.sdk.errors.QTSAuthError when no API key is
     *         available or the initial JWT exchange fails.
     */
    public static AuthenticatedClient authenticate(String apikey) {
        return AuthenticatedClient.authenticate(apikey);
    }

    /** Overload accepting an {@link AuthOptions} (base URL, token store, executor). */
    public static AuthenticatedClient authenticate(String apikey, AuthOptions options) {
        return AuthenticatedClient.authenticate(apikey, options);
    }

    /** Overload that reads the API key from {@code QTSURFER_APIKEY}. */
    public static AuthenticatedClient authenticate() {
        return AuthenticatedClient.authenticate();
    }

    /** Start building a {@link QTSurfer} client via the fluent {@link Builder}. */
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
            HttpStrategyCompileClient compileClient = new HttpStrategyCompileClient(apiClient);
            BacktestWorkflow workflow = new BacktestWorkflow(compileClient, backtestingApi, exec);
            SweepWorkflow sweeps = new SweepWorkflow(compileClient, backtestingApi, exec);
            return new QTSurfer(opts, workflow, sweeps, new ExchangeBinaryDownloads(apiClient),
                    new ExchangeApi(apiClient), new StrategyApi(apiClient));
        }
    }
}
