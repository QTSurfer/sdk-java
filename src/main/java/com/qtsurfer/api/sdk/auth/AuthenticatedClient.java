package com.qtsurfer.api.sdk.auth;

import com.qtsurfer.api.client.api.AuthApi;
import com.qtsurfer.api.client.api.BacktestingApi;
import com.qtsurfer.api.client.api.ExchangeApi;
import com.qtsurfer.api.client.binary.ExchangeBinaryDownloads;
import com.qtsurfer.api.client.invoker.ApiClient;
import com.qtsurfer.api.client.invoker.ApiException;
import com.qtsurfer.api.client.model.AuthTokenResponse;
import com.qtsurfer.api.client.model.Exchange;
import com.qtsurfer.api.client.model.InstrumentDetail;
import com.qtsurfer.api.client.model.ResultMap;
import com.qtsurfer.api.sdk.BacktestOptions;
import com.qtsurfer.api.sdk.BacktestRequest;
import com.qtsurfer.api.sdk.DownloadFormat;
import com.qtsurfer.api.sdk.Strategy;
import com.qtsurfer.api.sdk.errors.QTSAuthError;
import com.qtsurfer.api.sdk.errors.QTSDownloadError;
import com.qtsurfer.api.sdk.errors.QTSError;
import com.qtsurfer.api.sdk.internal.HttpStrategyCompileClient;
import com.qtsurfer.api.sdk.workflows.BacktestWorkflow;

import java.io.InputStream;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Authenticated SDK session.
 *
 * <p>Created by {@link com.qtsurfer.api.sdk.QTSurfer#authenticate(String)} (or the
 * overload that accepts an {@link AuthOptions}). Wraps the underlying
 * api-client, owns a JWT (in memory by default, or in the provided
 * {@link TokenStore}), and transparently re-exchanges the API key for a
 * fresh JWT when a call returns 401.
 *
 * <p>Exposes the same workflow surface as {@code QTSurfer}: {@code compile},
 * {@code backtest}, {@code exchanges}, {@code instruments}, {@code tickers},
 * {@code klines}. Method semantics are unchanged — only the bearer token
 * management differs.
 *
 * <p>Refresh policy: a 401 from any call routed through the generated
 * api-client (prepare, execute, result polling, exchanges, instruments,
 * tickers, klines) triggers exactly one {@code POST /v1/auth/token}
 * exchange, then the original call is retried once; a second 401 is
 * surfaced to the caller. The one exception is {@code compile}, which talks
 * to its endpoint directly and does not participate in this retry — see
 * {@link #compile(String, BacktestOptions)}.
 */
public final class AuthenticatedClient {

    static final String APIKEY_ENV_VAR = "QTSURFER_APIKEY";

    private final AuthOptions options;
    private final AuthApi authApi;
    private final BacktestWorkflow backtestWorkflow;
    private final ExchangeBinaryDownloads downloads;
    private final ExchangeApi exchangeApi;
    private final AtomicReference<AuthTokenResponse> cached = new AtomicReference<>();

    AuthenticatedClient(
            AuthOptions options,
            AuthApi authApi,
            BacktestWorkflow backtestWorkflow,
            ExchangeBinaryDownloads downloads,
            ExchangeApi exchangeApi) {
        this.options = options;
        this.authApi = authApi;
        this.backtestWorkflow = backtestWorkflow;
        this.downloads = downloads;
        this.exchangeApi = exchangeApi;
    }

    /** Configuration in use by this session. */
    public AuthOptions options() { return options; }

    /** Most recently minted token, or {@code null} if no exchange has happened yet. */
    public AuthTokenResponse token() { return cached.get(); }

    /**
     * Force a fresh JWT exchange via {@code POST /v1/auth/token}. Bypasses
     * the cache; the returned token is also written to the configured
     * {@link TokenStore}.
     */
    public synchronized AuthTokenResponse refresh() {
        AuthTokenResponse fresh;
        try {
            fresh = authApi.authenticate();
        } catch (ApiException e) {
            throw new QTSAuthError("authenticate() failed: HTTP " + e.getCode(), e);
        }
        if (fresh == null) {
            throw new QTSAuthError("authenticate() returned an empty response");
        }
        cached.set(fresh);
        mirror();
        options.store().save(fresh);
        return fresh;
    }

    /**
     * Return the cached token, seeding from the {@link TokenStore} on first
     * use, and minting a new one if neither cache nor store hold one.
     */
    public AuthTokenResponse ensureToken() {
        AuthTokenResponse t = cached.get();
        if (t != null) return t;
        AuthTokenResponse stored = options.store().load();
        if (stored != null) {
            cached.set(stored);
            mirror();
            return stored;
        }
        return refresh();
    }

    /** Drop the cached token (in memory and in the store). */
    public void clear() {
        cached.set(null);
        mirror();
        options.store().clear();
    }

    // ---- Workflow surface (mirrors QTSurfer) ----

    /** Equivalent to {@link #compile(String, BacktestOptions)} with {@link BacktestOptions#defaults()}. */
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
     * submitted code and will not succeed by retrying alone. Only
     * {@code opts.onProgress()} is used; polling and timeout settings do
     * not apply to this stage.
     *
     * <p>Two behaviors this session normally guarantees do not apply here:
     * a {@code 401} from the compile endpoint is <em>not</em> retried
     * after a token refresh (the endpoint is called directly rather than
     * through the generated client, so the failure is never recognized as
     * unauthorized), and token resolution itself happens synchronously
     * before the request is sent — on a session with no cached or stored
     * token, this call blocks to mint one and throws
     * {@link com.qtsurfer.api.sdk.errors.QTSAuthError} directly rather
     * than through the returned future.
     */
    public CompletableFuture<Strategy> compile(String source, BacktestOptions opts) {
        Objects.requireNonNull(source, "source");
        return withRefreshOn401Async(() -> backtestWorkflow.compile(source, opts));
    }

    /**
     * Convenience overload that compiles {@link BacktestRequest#strategy()}
     * with {@link BacktestOptions#defaults()}, ignoring every other field
     * of the request. See {@link #compile(String, BacktestOptions)}.
     */
    public CompletableFuture<Strategy> compile(BacktestRequest request) {
        Objects.requireNonNull(request, "request");
        return compile(request.strategy(), BacktestOptions.defaults());
    }

    /**
     * Convenience overload that compiles {@link BacktestRequest#strategy()}
     * with the given options, ignoring every other field of the request.
     * See {@link #compile(String, BacktestOptions)}.
     */
    public CompletableFuture<Strategy> compile(BacktestRequest request, BacktestOptions opts) {
        Objects.requireNonNull(request, "request");
        return compile(request.strategy(), opts);
    }

    /** Equivalent to {@link #backtest(BacktestRequest, BacktestOptions)} with {@link BacktestOptions#defaults()}. */
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
     * <p>A {@code 401} during prepare, execute, or result polling triggers
     * one token refresh and then restarts the <em>entire</em> pipeline
     * from compile — not just the stage that failed. A {@code 401} from
     * the compile stage itself is not retried; see
     * {@link #compile(String, BacktestOptions)}.
     *
     * <p>This call resolves the session's token synchronously before
     * scheduling any async work: on a session with no cached or stored
     * token, it blocks to mint one and throws
     * {@link com.qtsurfer.api.sdk.errors.QTSAuthError} directly (not
     * through the returned future) if that mint fails.
     *
     * @param opts tuning knobs (poll interval, timeout, progress callback)
     *             applied to every stage of the pipeline
     */
    public CompletableFuture<ResultMap> backtest(BacktestRequest request, BacktestOptions opts) {
        Objects.requireNonNull(request, "request");
        return withRefreshOn401Async(() -> backtestWorkflow.runFull(request, opts));
    }

    /**
     * List available exchanges on the platform. Synchronous — blocks the
     * calling thread for the HTTP round trip. Participates in the
     * session's refresh-on-401 policy: an unauthorized response triggers
     * one token refresh and one retry of this call.
     *
     * @throws QTSError on HTTP 4xx/5xx or transport failure
     */
    public List<Exchange> exchanges() {
        return withRefreshOn401(() -> {
            try {
                return exchangeApi.listExchanges();
            } catch (ApiException e) {
                throw new QTSError("exchanges call failed: " + describe(e), e);
            }
        });
    }

    /**
     * List instruments available on the given exchange, including
     * per-data-type coverage and market info. Synchronous — blocks the
     * calling thread for the HTTP round trip. Participates in the
     * session's refresh-on-401 policy: an unauthorized response triggers
     * one token refresh and one retry of this call.
     *
     * @param exchangeId exchange identifier (e.g. {@code "binance"})
     * @throws QTSError on HTTP 4xx/5xx or transport failure
     */
    public List<InstrumentDetail> instruments(String exchangeId) {
        Objects.requireNonNull(exchangeId, "exchangeId");
        return withRefreshOn401(() -> {
            try {
                return exchangeApi.listInstruments(exchangeId).getData();
            } catch (ApiException e) {
                throw new QTSError("instruments call failed: " + describe(e), e);
            }
        });
    }

    /** Equivalent to {@link #tickers(String, String, String, String, DownloadFormat)} with {@link DownloadFormat#LASTRA}. */
    public InputStream tickers(String exchangeId, String base, String quote, String hour) {
        return tickers(exchangeId, base, quote, hour, DownloadFormat.LASTRA);
    }

    /**
     * Download one hour of raw tickers for an instrument as a streaming
     * {@link InputStream}. Synchronous; the caller is responsible for
     * closing the returned stream. Participates in the session's
     * refresh-on-401 policy: an unauthorized response triggers one token
     * refresh and one retry of this call.
     *
     * @throws QTSDownloadError on HTTP 4xx/5xx or transport failure
     */
    public InputStream tickers(String exchangeId, String base, String quote, String hour, DownloadFormat format) {
        Objects.requireNonNull(format, "format");
        return withRefreshOn401(() -> {
            try {
                return downloads.getTickersHour(exchangeId, base, quote, hour, format.wire());
            } catch (ApiException e) {
                throw new QTSDownloadError("tickers download failed: " + describe(e), e);
            }
        });
    }

    /** Equivalent to {@link #klines(String, String, String, String, DownloadFormat)} with {@link DownloadFormat#LASTRA}. */
    public InputStream klines(String exchangeId, String base, String quote, String hour) {
        return klines(exchangeId, base, quote, hour, DownloadFormat.LASTRA);
    }

    /**
     * Download one hour of klines for an instrument as a streaming
     * {@link InputStream}. See
     * {@link #tickers(String, String, String, String, DownloadFormat)} for
     * blocking, closing, and refresh semantics.
     *
     * @throws QTSDownloadError on HTTP 4xx/5xx or transport failure
     */
    public InputStream klines(String exchangeId, String base, String quote, String hour, DownloadFormat format) {
        Objects.requireNonNull(format, "format");
        return withRefreshOn401(() -> {
            try {
                return downloads.getKlinesHour(exchangeId, base, quote, hour, format.wire());
            } catch (ApiException e) {
                throw new QTSDownloadError("klines download failed: " + describe(e), e);
            }
        });
    }

    // ---- Refresh-on-401 plumbing ----

    private <T> T withRefreshOn401(Supplier<T> call) {
        ensureToken();
        try {
            return call.get();
        } catch (QTSError e) {
            if (!isUnauthorized(e)) throw e;
            cached.set(null);
            mirror();
            refresh();
            return call.get();
        }
    }

    private <T> CompletableFuture<T> withRefreshOn401Async(Supplier<CompletableFuture<T>> call) {
        ensureToken();
        return call.get().handle((value, ex) -> {
            if (ex == null) return CompletableFuture.completedFuture(value);
            Throwable cause = unwrap(ex);
            if (!(cause instanceof QTSError qts) || !isUnauthorized(qts)) {
                CompletableFuture<T> fail = new CompletableFuture<>();
                fail.completeExceptionally(ex);
                return fail;
            }
            cached.set(null);
            mirror();
            refresh();
            return call.get();
        }).thenCompose(f -> f);
    }

    private static boolean isUnauthorized(QTSError e) {
        Throwable c = e.getCause();
        return c instanceof ApiException api && api.getCode() == 401;
    }

    private static Throwable unwrap(Throwable t) {
        if (t instanceof CompletionException ce && ce.getCause() != null) {
            return ce.getCause();
        }
        return t;
    }

    private static String describe(ApiException e) {
        if (e.getResponseBody() != null && !e.getResponseBody().isBlank()) {
            return "HTTP " + e.getCode() + " — " + e.getResponseBody();
        }
        return "HTTP " + e.getCode();
    }

    // ---- Factory ----

    /**
     * Mint a fresh session from the given API key.
     *
     * <p>If {@code apikey} is {@code null} or blank, the value is read from
     * the {@code QTSURFER_APIKEY} environment variable. A {@link QTSAuthError}
     * is raised when neither source yields a usable API key, or when the
     * initial JWT exchange fails.
     */
    public static AuthenticatedClient authenticate(String apikey, AuthOptions opts) {
        String resolved = resolveApikey(apikey);
        AuthOptions o = opts != null ? opts : AuthOptions.defaults();

        // ApiClient for the mint call — interceptor pins X-API-Key.
        ApiClient mintClient = buildApiClient(o, b -> b.header("X-API-Key", resolved));
        AuthApi mintApi = new AuthApi(mintClient);

        // Container ref shared between the request interceptor and the
        // session itself, so refresh() swapping the cached token is visible
        // to subsequent api-client calls without rebuilding the client.
        AtomicReference<AuthTokenResponse> shared = new AtomicReference<>();
        ApiClient apiClient = buildApiClient(o, b -> {
            AuthTokenResponse t = shared.get();
            if (t != null && t.getAccessToken() != null) {
                b.header("Authorization", "Bearer " + t.getAccessToken());
            }
        });

        BacktestingApi backtestingApi = new BacktestingApi(apiClient);
        ExecutorService exec = o.executor() != null ? o.executor() : ForkJoinPool.commonPool();
        BacktestWorkflow workflow = new BacktestWorkflow(
                new HttpStrategyCompileClient(apiClient), backtestingApi, exec);
        ExchangeBinaryDownloads downloads = new ExchangeBinaryDownloads(apiClient);
        ExchangeApi exchangeApi = new ExchangeApi(apiClient);

        AuthenticatedClient session = new AuthenticatedClient(
                o, mintApi, workflow, downloads, exchangeApi);
        // Keep `shared` mirrored to the session's cache via a bridge thread-safely.
        session.linkBearerRef(shared);

        // Initial mint so the session is usable immediately.
        session.ensureToken();
        return session;
    }

    /** Equivalent to {@link #authenticate(String, AuthOptions)} with {@link AuthOptions#defaults()}. */
    public static AuthenticatedClient authenticate(String apikey) {
        return authenticate(apikey, AuthOptions.defaults());
    }

    /**
     * Equivalent to {@link #authenticate(String, AuthOptions)} with a
     * {@code null} apikey (resolved from {@code QTSURFER_APIKEY}) and
     * {@link AuthOptions#defaults()}.
     */
    public static AuthenticatedClient authenticate() {
        return authenticate(null, AuthOptions.defaults());
    }

    /**
     * Wire the bearer-token interceptor's reference to this session's cache.
     * Called by the factory.
     */
    private void linkBearerRef(AtomicReference<AuthTokenResponse> bearerRef) {
        this.bearerRef = bearerRef;
        AuthTokenResponse current = cached.get();
        if (current != null) bearerRef.set(current);
    }

    private volatile AtomicReference<AuthTokenResponse> bearerRef;

    private void mirror() {
        AtomicReference<AuthTokenResponse> r = bearerRef;
        if (r != null) r.set(cached.get());
    }

    private static ApiClient buildApiClient(AuthOptions opts, Consumer<HttpRequest.Builder> headers) {
        ApiClient c = new ApiClient();
        c.updateBaseUri(opts.baseUrl().toString());
        c.setRequestInterceptor(headers);
        return c;
    }

    private static String resolveApikey(String explicit) {
        return resolveApikey(explicit, System.getenv(APIKEY_ENV_VAR));
    }

    /** Package-private overload exposed for unit tests. */
    static String resolveApikey(String explicit, String envValue) {
        if (explicit != null && !explicit.isBlank()) return explicit;
        if (envValue != null && !envValue.isBlank()) return envValue;
        throw new QTSAuthError(
                "authenticate() requires an apikey (argument or " + APIKEY_ENV_VAR + " env var)");
    }
}
