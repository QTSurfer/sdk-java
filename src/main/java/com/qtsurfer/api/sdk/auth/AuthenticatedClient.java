package com.qtsurfer.api.sdk.auth;

import com.qtsurfer.api.client.api.AuthApi;
import com.qtsurfer.api.client.api.BacktestingApi;
import com.qtsurfer.api.client.api.ExchangeApi;
import com.qtsurfer.api.client.api.StrategyApi;
import com.qtsurfer.api.client.binary.ExchangeBinaryDownloads;
import com.qtsurfer.api.client.invoker.ApiClient;
import com.qtsurfer.api.client.invoker.ApiException;
import com.qtsurfer.api.client.model.AuthTokenResponse;
import com.qtsurfer.api.client.model.Exchange;
import com.qtsurfer.api.client.model.InstrumentDetail;
import com.qtsurfer.api.client.model.ResultMap;
import com.qtsurfer.api.client.model.StrategyState;
import com.qtsurfer.api.sdk.BacktestOptions;
import com.qtsurfer.api.sdk.BacktestOutcome;
import com.qtsurfer.api.sdk.BacktestRequest;
import com.qtsurfer.api.sdk.DownloadFormat;
import com.qtsurfer.api.sdk.Strategy;
import com.qtsurfer.api.sdk.Sweep;
import com.qtsurfer.api.sdk.SweepOptions;
import com.qtsurfer.api.sdk.SweepRequest;
import com.qtsurfer.api.sdk.ValidationOutcome;
import com.qtsurfer.api.sdk.errors.QTSAuthError;
import com.qtsurfer.api.sdk.errors.QTSDownloadError;
import com.qtsurfer.api.sdk.errors.QTSError;
import com.qtsurfer.api.sdk.internal.HttpStrategyCompileClient;
import com.qtsurfer.api.sdk.internal.ValidationOutcomes;
import com.qtsurfer.api.sdk.workflows.BacktestWorkflow;
import com.qtsurfer.api.sdk.workflows.SweepWorkflow;

import java.io.InputStream;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.Instant;
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
 * {@code validateStrategy}, {@code strategyState}, {@code backtest},
 * {@code backtestResult}, {@code sweep}, {@code exchanges},
 * {@code instruments}, {@code tickers}, {@code klines}.
 * Method semantics are unchanged — only the bearer token management differs.
 *
 * <p>Refresh policy: every call first checks the cached token's known
 * {@code expires_in} window and proactively re-exchanges it (same
 * {@code POST /v1/auth/token} call) a short margin before it would expire —
 * a session left idle past an hour mints a new token on the next call
 * instead of sending one already stale. That covers TTL expiry, but not a
 * token invalidated some other way; for that, a 401 from any call routed
 * through the generated api-client (prepare, execute, result polling and
 * standalone result reads, strategy validation and lookup, exchanges,
 * instruments, tickers, klines) <em>and</em> {@code compile} (which talks to
 * its endpoint directly but carries the same {@code ApiException} cause on
 * a 401) triggers one more {@code POST /v1/auth/token} exchange, then the
 * original call is retried once; a second 401 is surfaced to the caller.
 */
public final class AuthenticatedClient {

    static final String APIKEY_ENV_VAR = "QTSURFER_APIKEY";

    /** Fallback TTL when a token response omits {@code expires_in}. */
    private static final long DEFAULT_TTL_SECONDS = 3600;

    /**
     * Refresh this far ahead of the token's known expiry, so the call that
     * triggers a proactive refresh doesn't itself race the token going
     * stale mid-flight.
     */
    private static final Duration REFRESH_SKEW = Duration.ofSeconds(30);

    private final AuthOptions options;
    private final AuthApi authApi;
    private final BacktestWorkflow backtestWorkflow;
    private final SweepWorkflow sweepWorkflow;
    private final ExchangeBinaryDownloads downloads;
    private final ExchangeApi exchangeApi;
    private final StrategyApi strategyApi;
    private final AtomicReference<AuthTokenResponse> cached = new AtomicReference<>();

    /**
     * When the token in {@link #cached} should be treated as stale, for a
     * token minted by this session via {@link #refresh()}. {@code null}
     * for a token seeded from the {@link TokenStore} instead — its actual
     * remaining TTL is unknown, so it is trusted at face value the same
     * way it always has been (unchanged behavior for that path).
     */
    private final AtomicReference<Instant> expiresAt = new AtomicReference<>();

    AuthenticatedClient(
            AuthOptions options,
            AuthApi authApi,
            BacktestWorkflow backtestWorkflow,
            SweepWorkflow sweepWorkflow,
            ExchangeBinaryDownloads downloads,
            ExchangeApi exchangeApi,
            StrategyApi strategyApi) {
        this.options = options;
        this.authApi = authApi;
        this.backtestWorkflow = backtestWorkflow;
        this.sweepWorkflow = sweepWorkflow;
        this.downloads = downloads;
        this.exchangeApi = exchangeApi;
        this.strategyApi = strategyApi;
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
        expiresAt.set(computeExpiry(fresh));
        mirror();
        options.store().save(fresh);
        return fresh;
    }

    /**
     * Return the cached token, seeding from the {@link TokenStore} on first
     * use, minting a new one if neither cache nor store hold one, and
     * proactively re-minting one this session already minted once its
     * {@code expires_in} window (minus {@link #REFRESH_SKEW}) has elapsed —
     * so a session idle past that window mints on the next call instead of
     * sending a token the platform will reject.
     */
    public AuthTokenResponse ensureToken() {
        AuthTokenResponse t = cached.get();
        if (t != null) {
            Instant exp = expiresAt.get();
            if (exp == null || Instant.now().isBefore(exp)) {
                return t;
            }
            return refresh();
        }
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
        expiresAt.set(null);
        mirror();
        options.store().clear();
    }

    private static Instant computeExpiry(AuthTokenResponse token) {
        Integer seconds = token.getExpiresIn();
        long ttl = (seconds != null && seconds > 0) ? seconds : DEFAULT_TTL_SECONDS;
        Duration window = Duration.ofSeconds(ttl).minus(REFRESH_SKEW);
        if (window.isNegative()) window = Duration.ZERO;
        return Instant.now().plus(window);
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
     * <p>Participates in the session's refresh-on-401 policy the same as
     * every other call — both the proactive TTL check before the request is
     * sent and one retry after a reactive refresh if the platform still
     * returns {@code 401} (the compile endpoint is called directly rather
     * than through the generated client, but its {@code 401} carries the
     * same {@code ApiException} cause so this session recognizes it). One
     * behavior this session normally guarantees does not apply here: token
     * resolution happens synchronously before the request is sent — on a
     * session with no cached or stored token, this call blocks to mint one
     * and throws {@link com.qtsurfer.api.sdk.errors.QTSAuthError} directly
     * rather than through the returned future.
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

    /**
     * Ask the platform to check that a registered strategy can actually run.
     * The compiled class is instantiated and driven through a bounded
     * synthetic series, so a wiring fault surfaces here instead of at the
     * first backtest. Synchronous — blocks the calling thread for the HTTP
     * round trip. Participates in the session's refresh-on-401 policy: an
     * unauthorized response triggers one token refresh and one retry of this
     * call. Because the call is idempotent, that retry queues nothing extra.
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
        return withRefreshOn401(() -> {
            try {
                return ValidationOutcomes.of(
                        strategyId, strategyApi.validateStrategyWithHttpInfo(strategyId));
            } catch (ApiException e) {
                throw new QTSError("validateStrategy call failed: " + describe(e), e);
            }
        });
    }

    /**
     * Fetch what the platform knows about a registered strategy: that it
     * compiled, what market data the compiled class needs, and what
     * validating it found. Synchronous — blocks the calling thread for the
     * HTTP round trip. Participates in the session's refresh-on-401 policy:
     * an unauthorized response triggers one token refresh and one retry of
     * this call.
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
        return withRefreshOn401(() -> {
            try {
                return strategyApi.getStrategy(strategyId);
            } catch (ApiException e) {
                throw new QTSError("strategyState call failed: " + describe(e), e);
            }
        });
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
     * <p>A {@code 401} at any stage — including compile, see
     * {@link #compile(String, BacktestOptions)} — triggers one token
     * refresh and then restarts the <em>entire</em> pipeline from compile,
     * not just the stage that failed.
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
     * Read what the platform holds for a backtest run, addressed by the
     * exchange it ran on and the id of its execute job. Synchronous — blocks
     * the calling thread for the HTTP round trip. Participates in the
     * session's refresh-on-401 policy: an unauthorized response triggers one
     * token refresh and one retry of this call. The call only reads, so that
     * retry starts nothing extra.
     *
     * <p><strong>The run does not have to be one this session started.</strong>
     * Every other route to a run's numbers in this SDK goes through the handle
     * {@link #backtest(BacktestRequest)} or
     * {@link Strategy#backtest(BacktestRequest)} hands back, and that handle
     * only exists in the process that submitted the run. A job id that arrived
     * from anywhere else — another client, another session, this one before a
     * restart — has no handle behind it, and this is how to ask the platform
     * about it directly. It compiles nothing, prepares nothing, submits
     * nothing, and starts no second run.
     *
     * <p><strong>A run that ended badly is an answer, not a failure of this
     * call.</strong> The {@link BacktestOutcome} handed back says which of
     * four things the platform is reporting — the run finished, it failed, it
     * was cancelled, or it is still going and has nothing final to say yet.
     * Only a job the platform does not recognise for this caller raises, as a
     * {@code 404}. That is a deliberate departure from
     * {@link com.qtsurfer.api.sdk.Backtest#await()}, which completes
     * exceptionally on a failed or aborted run: a caller waiting for a result
     * it asked for is not getting one, whereas a caller asking what happened
     * to a job is.
     *
     * <p>Waiting for a run to finish remains
     * {@link com.qtsurfer.api.sdk.Backtest#await()}'s job, on the process that
     * started it. This does not poll — it is a snapshot, and
     * {@link BacktestOutcome.InProgress} means ask again later.
     *
     * <p><strong>{@code exchangeId} is required and cannot be guessed.</strong>
     * A run's result is addressed under the exchange it was submitted against,
     * so a job id on its own does not identify the resource and there is
     * nothing sensible to default the exchange to. An id carried to the wrong
     * exchange does not name the same run.
     *
     * @param exchangeId exchange the run was submitted against (e.g. {@code "binance"})
     * @param jobId      id of the execute job, as carried by
     *                   {@link com.qtsurfer.api.sdk.Backtest#id()} on the
     *                   process that submitted it
     * @throws QTSError on HTTP 4xx/5xx (including the {@code 404} above) or
     *                  transport failure
     */
    public BacktestOutcome backtestResult(String exchangeId, String jobId) {
        Objects.requireNonNull(exchangeId, "exchangeId");
        Objects.requireNonNull(jobId, "jobId");
        return withRefreshOn401(() -> backtestWorkflow.readResult(exchangeId, jobId));
    }

    /** Equivalent to {@link #sweep(SweepRequest, SweepOptions)} with {@link SweepOptions#defaults()}.
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
     * keeps polling the leaderboard in the background. See
     * {@link com.qtsurfer.api.sdk.QTSurfer#sweep(SweepRequest, SweepOptions)}
     * for why the sweep is one call rather than composable stages, and
     * {@link Sweep#await()} for how to read what it found.
     *
     * <p>A {@code 401} during compile (see
     * {@link #compile(String, BacktestOptions)}), prepare, or submission
     * triggers one token refresh and then restarts the <em>entire</em>
     * pipeline from compile — not just the stage that failed. One limit is
     * worth knowing: this does not cover the background leaderboard poll,
     * which starts after this future has already resolved and surfaces on
     * {@link Sweep#await()} instead. The handle-scoped
     * {@link Sweep#sensitivity()} and {@link Sweep#cancel()} sit outside the
     * policy for the same reason.
     *
     * <p>This call resolves the session's token synchronously before
     * scheduling any async work: on a session with no cached or stored token,
     * it blocks to mint one and throws
     * {@link com.qtsurfer.api.sdk.errors.QTSAuthError} directly (not through
     * the returned future) if that mint fails.
     *
     * @param request the grid, the instrument, and the window
     * @param opts    tuning knobs (poll interval, timeout, progress callback,
     *                leaderboard ordering) applied to every stage of the pipeline
     * @return the handle, once the platform has accepted the sweep
     */
    public CompletableFuture<Sweep> sweep(SweepRequest request, SweepOptions opts) {
        Objects.requireNonNull(request, "request");
        return withRefreshOn401Async(() -> sweepWorkflow.submit(request, opts));
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

    /**
     * List the instruments of one market segment of the given exchange,
     * including per-data-type coverage and market info. Synchronous — blocks
     * the calling thread for the HTTP round trip. Participates in the
     * session's refresh-on-401 policy: an unauthorized response triggers one
     * token refresh and one retry of this call.
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
        return withRefreshOn401(() -> {
            try {
                return exchangeApi.listSegmentInstruments(exchangeId, segment).getData();
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
        HttpStrategyCompileClient compileClient = new HttpStrategyCompileClient(apiClient);
        BacktestWorkflow workflow = new BacktestWorkflow(compileClient, backtestingApi, exec);
        SweepWorkflow sweeps = new SweepWorkflow(compileClient, backtestingApi, exec);
        ExchangeBinaryDownloads downloads = new ExchangeBinaryDownloads(apiClient);
        ExchangeApi exchangeApi = new ExchangeApi(apiClient);
        StrategyApi strategyApi = new StrategyApi(apiClient);

        AuthenticatedClient session = new AuthenticatedClient(
                o, mintApi, workflow, sweeps, downloads, exchangeApi, strategyApi);
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
