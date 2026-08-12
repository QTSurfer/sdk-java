package com.qtsurfer.api.sdk.workflows;

import com.qtsurfer.api.client.api.BacktestingApi;
import com.qtsurfer.api.client.model.DataSourceType;
import com.qtsurfer.api.client.model.ExecuteSweepAccepted;
import com.qtsurfer.api.client.model.ExecuteSweepRequest;
import com.qtsurfer.api.client.model.ExecuteSweepResult;
import com.qtsurfer.api.client.model.PrepareRequest;
import com.qtsurfer.api.client.model.SweepAxis;
import com.qtsurfer.api.client.model.SweepProgress;
import com.qtsurfer.api.client.model.SweepSensitivity;
import com.qtsurfer.api.client.model.SweepSpecRequest;
import com.qtsurfer.api.client.model.WalkForwardRequest;
import com.qtsurfer.api.sdk.BacktestStage;
import com.qtsurfer.api.sdk.Sweep;
import com.qtsurfer.api.sdk.Sweep.State;
import com.qtsurfer.api.sdk.SweepObjective;
import com.qtsurfer.api.sdk.SweepOptions;
import com.qtsurfer.api.sdk.SweepOrder;
import com.qtsurfer.api.sdk.SweepProgressEvent;
import com.qtsurfer.api.sdk.SweepRanking;
import com.qtsurfer.api.sdk.SweepRequest;
import com.qtsurfer.api.sdk.WalkForwardSpec;
import com.qtsurfer.api.sdk.errors.QTSCanceledError;
import com.qtsurfer.api.sdk.errors.QTSError;
import com.qtsurfer.api.sdk.errors.QTSExecutionError;
import com.qtsurfer.api.sdk.errors.QTSStrategyCompileError;
import com.qtsurfer.api.sdk.internal.ApiCalls;
import com.qtsurfer.api.sdk.internal.Polling;
import com.qtsurfer.api.sdk.internal.Preparation;
import com.qtsurfer.api.sdk.internal.StatusNormalizer;
import com.qtsurfer.api.sdk.internal.StatusNormalizer.Normalized;
import com.qtsurfer.api.sdk.internal.StrategyCompileClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Orchestrates compile → prepare → executeSweep over
 * {@code com.qtsurfer:api-client}, and polls the leaderboard until the sweep
 * stops advancing. Sibling of {@link BacktestWorkflow}, sharing its prepare
 * stage and its polling loop.
 *
 * <p><strong>One call, not composable stages, and deliberately so.</strong> The
 * execute-sweep endpoint is addressed by the id of an already-prepared dataset,
 * so a stage-level API would hand dataset lifecycle to the caller for no gain.
 * Preparing is idempotent — the same instrument and window always resolve to
 * the same job — so preparing on every sweep duplicates no work.
 */
public final class SweepWorkflow {

    private static final Logger log = LoggerFactory.getLogger(Sweep.class);
    private static final DataSourceType TICKER = DataSourceType.TICKER;

    private final StrategyCompileClient strategyClient;
    private final BacktestingApi backtestingApi;
    private final Executor executor;

    /**
     * @param strategyClient  compiles strategy source
     * @param backtestingApi  the generated backtesting api
     * @param executor        runs the submission and the background poll
     */
    public SweepWorkflow(
            StrategyCompileClient strategyClient,
            BacktestingApi backtestingApi,
            Executor executor) {
        this.strategyClient = Objects.requireNonNull(strategyClient, "strategyClient");
        this.backtestingApi = Objects.requireNonNull(backtestingApi, "backtestingApi");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /**
     * Compile, prepare, and submit the sweep. Resolves with a {@link Sweep}
     * once the platform has accepted it; the leaderboard keeps being polled in
     * the background to feed progress events and resolve
     * {@link Sweep#await()}.
     *
     * @param req  the grid, the instrument, and the window
     * @param opts tuning knobs; {@code null} takes {@link SweepOptions#defaults()}
     * @return the handle, once accepted
     */
    public CompletableFuture<Sweep> submit(SweepRequest req, SweepOptions opts) {
        Objects.requireNonNull(req, "request");
        SweepOptions safeOpts = opts != null ? opts : SweepOptions.defaults();

        return CompletableFuture.supplyAsync(() -> {
            emit(safeOpts.onProgress(), new SweepProgressEvent(BacktestStage.COMPILING, null));
            String strategyId = compileStrategy(req.strategy());

            emit(safeOpts.onProgress(), new SweepProgressEvent(BacktestStage.PREPARING, null));
            String requestId = prepareData(req, safeOpts);

            emit(safeOpts.onProgress(), new SweepProgressEvent(BacktestStage.EXECUTING, null));
            ExecuteSweepAccepted accepted = ApiCalls.call(
                    () -> backtestingApi.executeSweep(
                            req.exchangeId(), TICKER, requestId, buildSweepBody(req, strategyId)),
                    "Sweep submission failed",
                    QTSExecutionError::new);
            if (accepted == null || accepted.getSweepId() == null) {
                throw new QTSExecutionError("Missing sweepId in executeSweep response");
            }
            // The handle addresses the sweep with the requestId of the dataset just prepared —
            // the value this workflow already knows — rather than the acceptance echo of it.
            return buildSweep(accepted, requestId, strategyId, req, safeOpts);
        }, executor);
    }

    private String prepareData(SweepRequest req, SweepOptions opts) {
        PrepareRequest body = new PrepareRequest()
                .instrument(req.instrument())
                .from(req.from())
                .to(req.to());
        return Preparation.prepare(
                backtestingApi, req.exchangeId(), TICKER, body,
                opts.pollInterval(), opts.maxPollInterval(), opts.timeout(),
                percent -> emit(opts.onProgress(), new SweepProgressEvent(BacktestStage.PREPARING, percent)),
                // A thinly covered window is about to be scored once per parameter vector, so the
                // coverage ratio is worth at least as much here as on a single backtest.
                state -> emit(opts.onProgress(), new SweepProgressEvent(
                        BacktestStage.PREPARING, 100.0, state.getCoverageRatio(), null)));
    }

    private ExecuteSweepRequest buildSweepBody(SweepRequest req, String strategyId) {
        Map<String, SweepAxis> params = new LinkedHashMap<>();
        req.params().forEach((name, axis) -> params.put(name, axis.wire()));

        SweepSpecRequest spec = new SweepSpecRequest().params(params);
        if (req.sampler() != null) spec.sampler(req.sampler().wire());
        if (req.samples() != null) spec.samples(req.samples());
        if (req.seed() != null) spec.seed(req.seed());
        if (req.objective() != null) {
            spec.objective(SweepSpecRequest.ObjectiveEnum.fromValue(req.objective().wire()));
        }

        ExecuteSweepRequest body = new ExecuteSweepRequest()
                .strategyId(strategyId)
                .sweep(spec);

        WalkForwardSpec wf = req.walkForward();
        if (wf != null) {
            WalkForwardRequest wfBody = new WalkForwardRequest().folds(wf.folds());
            if (wf.inSamplePct() != null) wfBody.inSamplePct(wf.inSamplePct());
            body.walkForward(wfBody);
        }
        return body;
    }

    private Sweep buildSweep(
            ExecuteSweepAccepted accepted, String requestId, String strategyId,
            SweepRequest req, SweepOptions opts) {

        String sweepId = accepted.getSweepId();
        SubmissionPublisher<SweepProgressEvent> publisher = new SubmissionPublisher<>(executor, 16);
        AtomicReference<State> state = new AtomicReference<>(State.EXECUTING);
        CompletableFuture<ExecuteSweepResult> resultFuture = new CompletableFuture<>();

        Consumer<SweepProgressEvent> progressSink = p -> {
            emit(opts.onProgress(), p);
            publisher.submit(p);
        };

        // Unlike a backtest, this does NOT cancel the polling future: a cancelled sweep keeps
        // every row it already finished, and the only way the SDK can hand those back is to keep
        // polling until the platform reports CANCELLED and then resolve normally.
        Runnable cancelHook = () -> {
            try {
                backtestingApi.cancelSweep(req.exchangeId(), TICKER, requestId, sweepId);
            } catch (Exception ignore) {
                // Best effort
            }
        };

        CompletableFuture.supplyAsync(
                () -> pollSweep(sweepId, requestId, req, opts, progressSink),
                executor
        ).whenComplete((result, err) -> {
            try {
                if (err != null) {
                    Throwable cause = unwrap(err);
                    // An interrupted poll is a cancellation, not a failure; everything else
                    // (transport, HTTP, stage timeout) is.
                    state.set(cause instanceof QTSCanceledError ? State.CANCELED : State.FAILED);
                    resultFuture.completeExceptionally(cause);
                    publisher.closeExceptionally(cause);
                } else {
                    state.set(isCancelled(result) ? State.CANCELED : State.COMPLETED);
                    resultFuture.complete(result);
                    publisher.close();
                }
            } catch (Throwable t) {
                log.warn("Sweep finalization raised", t);
            }
        });

        return new Sweep(
                accepted, requestId, strategyId, resultFuture, publisher, state, cancelHook,
                (order, ranking) -> readResults(
                        req.exchangeId(), requestId, sweepId, order, ranking, QTSError::new),
                objective -> readSensitivity(req.exchangeId(), requestId, sweepId, objective));
    }

    private ExecuteSweepResult pollSweep(
            String sweepId,
            String requestId,
            SweepRequest req,
            SweepOptions opts,
            Consumer<SweepProgressEvent> progressSink) {

        ExecuteSweepResult finalResult = Polling.poll(
                BacktestStage.EXECUTING.toString(),
                opts.pollInterval(), opts.maxPollInterval(), opts.timeout(),
                () -> readResults(req.exchangeId(), requestId, sweepId,
                        opts.order(), opts.ranking(), QTSExecutionError::new),
                r -> StatusNormalizer.normalize(statusOf(r)) == Normalized.IN_PROGRESS,
                r -> progressSink.accept(new SweepProgressEvent(
                        BacktestStage.EXECUTING, percentOf(r), null, r == null ? null : r.getProgress())));

        log.info("Sweep {} finished: status={} rows={} ranking={} walkForward={}",
                sweepId,
                statusOf(finalResult),
                finalResult != null ? finalResult.getLeaderboardSize() : null,
                finalResult != null ? finalResult.getRanking() : null,
                finalResult != null && finalResult.getWalkForward() != null);
        return finalResult;
    }

    /**
     * One read of the sweep's leaderboard. Both the background poll and the
     * handle's re-read go through here, so the view parameters are encoded in
     * exactly one place.
     *
     * <p>The error type is the caller's to choose, and it is the one thing the
     * two paths do not share. A transport fault under the poll is the sweep a
     * caller is waiting on failing to advance, which is what
     * {@link QTSExecutionError} means; the same fault under
     * {@link Sweep#results(SweepOrder, SweepRanking)} is just a read that did
     * not arrive, and is raised as a plain {@link QTSError} — the type
     * {@link #readSensitivity} has always used for the sibling read.
     */
    private ExecuteSweepResult readResults(
            String exchangeId, String requestId, String sweepId,
            SweepOrder order, SweepRanking ranking,
            java.util.function.BiFunction<String, Throwable, ? extends QTSError> errorCtor) {
        return ApiCalls.call(
                () -> backtestingApi.getSweepResult(
                        exchangeId, TICKER, requestId, sweepId, null,
                        order != null ? order.wire() : null,
                        ranking != null ? ranking.wire() : null),
                "Sweep result request failed",
                errorCtor);
    }

    private SweepSensitivity readSensitivity(
            String exchangeId, String requestId, String sweepId, SweepObjective objective) {
        return ApiCalls.call(
                () -> backtestingApi.getSweepSensitivity(
                        exchangeId, TICKER, requestId, sweepId,
                        objective != null ? objective.wire() : null),
                "Sweep sensitivity request failed",
                QTSError::new);
    }

    private String compileStrategy(String source) {
        String strategyId = strategyClient.compile(source);
        if (strategyId == null || strategyId.isBlank()) {
            throw new QTSStrategyCompileError("Compile response missing strategyId");
        }
        return strategyId;
    }

    /**
     * Status of a sweep snapshot, or {@code null} when the response carries
     * none. Reading it through this keeps a body-less response a poll rather
     * than a {@code NullPointerException} raised inside the retry predicate —
     * see {@code BacktestWorkflow#statusOf} for what that failure looks like.
     */
    private static ExecuteSweepResult.StatusEnum statusOf(ExecuteSweepResult result) {
        return result == null ? null : result.getStatus();
    }

    private static boolean isCancelled(ExecuteSweepResult result) {
        return statusOf(result) == ExecuteSweepResult.StatusEnum.CANCELLED;
    }

    /**
     * Percentage of the sweep's runs that have finished. Deliberately computed
     * from the run-level counts: the shard counts alongside them partition
     * units of work, not runs, and mixing the two reports a percentage of
     * neither.
     */
    private static Double percentOf(ExecuteSweepResult result) {
        if (result == null || result.getProgress() == null) return null;
        SweepProgress p = result.getProgress();
        if (p.getDone() == null || p.getTotal() == null || p.getTotal() <= 0) return null;
        return (p.getDone().doubleValue() / p.getTotal()) * 100.0;
    }

    private static void emit(Consumer<SweepProgressEvent> sink, SweepProgressEvent p) {
        if (sink == null) return;
        try {
            sink.accept(p);
        } catch (RuntimeException e) {
            log.warn("onProgress callback threw", e);
        }
    }

    private static Throwable unwrap(Throwable t) {
        if (t instanceof java.util.concurrent.CompletionException && t.getCause() != null) {
            return t.getCause();
        }
        return t;
    }
}
