package com.qtsurfer.api.sdk.workflows;

import com.qtsurfer.api.client.api.BacktestingApi;
import com.qtsurfer.api.client.model.AcceptedJob;
import com.qtsurfer.api.client.model.BacktestJobResult;
import com.qtsurfer.api.client.model.DataSourceType;
import com.qtsurfer.api.client.model.ExecuteBacktestRequest;
import com.qtsurfer.api.client.model.JobState;
import com.qtsurfer.api.client.model.PrepareRequest;
import com.qtsurfer.api.client.model.ResultMap;
import com.qtsurfer.api.sdk.Backtest;
import com.qtsurfer.api.sdk.Backtest.State;
import com.qtsurfer.api.sdk.BacktestOptions;
import com.qtsurfer.api.sdk.BacktestProgress;
import com.qtsurfer.api.sdk.BacktestRequest;
import com.qtsurfer.api.sdk.BacktestStage;
import com.qtsurfer.api.sdk.Strategy;
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

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Orchestrates compile → prepare → execute over {@code com.qtsurfer:api-client}.
 * Exposes the flow as three composable async primitives:
 * {@link #compile}, {@link #submitExecution}, and the shortcut {@link #runFull}.
 */
public final class BacktestWorkflow {

    private static final Logger log = LoggerFactory.getLogger(Backtest.class);
    private static final DataSourceType TICKER = DataSourceType.TICKER;

    private final StrategyCompileClient strategyClient;
    private final BacktestingApi backtestingApi;
    private final Executor executor;

    public BacktestWorkflow(
            StrategyCompileClient strategyClient,
            BacktestingApi backtestingApi,
            Executor executor) {
        this.strategyClient = Objects.requireNonNull(strategyClient, "strategyClient");
        this.backtestingApi = Objects.requireNonNull(backtestingApi, "backtestingApi");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /** Compile a strategy (synchronous on the wire) and return its handle. */
    public CompletableFuture<Strategy> compile(String source, BacktestOptions opts) {
        BacktestOptions safeOpts = opts != null ? opts : BacktestOptions.defaults();
        return CompletableFuture.supplyAsync(() -> {
            emit(safeOpts.onProgress(), new BacktestProgress(BacktestStage.COMPILING, null));
            String strategyId = compileStrategy(source);
            return new Strategy(strategyId, this);
        }, executor);
    }

    /**
     * Prepare + submit execute for the given strategy. Returns a {@link Backtest}
     * once the execute request has been accepted; polling of the execution
     * continues in the background to feed progress events and resolve
     * {@link Backtest#await()}.
     */
    public CompletableFuture<Backtest> submitExecution(
            Strategy strategy, BacktestRequest req, BacktestOptions opts) {
        BacktestOptions safeOpts = opts != null ? opts : BacktestOptions.defaults();

        return CompletableFuture.supplyAsync(() -> {
            emit(safeOpts.onProgress(), new BacktestProgress(BacktestStage.PREPARING, null));
            String prepareJobId = prepareData(req, safeOpts);

            emit(safeOpts.onProgress(), new BacktestProgress(BacktestStage.EXECUTING, null));
            AcceptedJob accepted = call(
                    () -> backtestingApi.executeBacktest(req.exchangeId(), TICKER, buildExecuteBody(req, strategy, prepareJobId)),
                    "Execute submission failed",
                    QTSExecutionError::new);
            if (accepted == null || accepted.getJobId() == null) {
                throw new QTSExecutionError("Missing jobId in execute response");
            }
            return buildJob(accepted.getJobId(), strategy, req, safeOpts);
        }, executor);
    }

    /** v0.1-compat shortcut: compile + prepare + execute + await, as a single future. */
    public CompletableFuture<ResultMap> runFull(BacktestRequest req, BacktestOptions opts) {
        return compile(req.strategy(), opts)
                .thenCompose(s -> submitExecution(s, req, opts))
                .thenCompose(Backtest::await);
    }

    private ExecuteBacktestRequest buildExecuteBody(
            BacktestRequest req, Strategy strategy, String prepareJobId) {
        ExecuteBacktestRequest body = new ExecuteBacktestRequest()
                .prepareJobId(prepareJobId)
                .strategyId(strategy.id());
        if (req.storeSignals() != null) {
            body.storeSignals(req.storeSignals());
        }
        return body;
    }

    private Backtest buildJob(
            String executeJobId, Strategy strategy, BacktestRequest req, BacktestOptions opts) {
        SubmissionPublisher<BacktestProgress> publisher = new SubmissionPublisher<>(executor, 16);
        AtomicReference<State> state = new AtomicReference<>(State.EXECUTING);
        CompletableFuture<ResultMap> resultFuture = new CompletableFuture<>();

        // Relay internal progress through the publisher AND the user's Consumer.
        Consumer<BacktestProgress> progressSink = p -> {
            emit(opts.onProgress(), p);
            publisher.submit(p);
        };

        Runnable cancelHook = () -> {
            resultFuture.cancel(true);
            try {
                backtestingApi.cancelBacktest(req.exchangeId(), TICKER, executeJobId);
            } catch (Exception ignore) {
                // Best effort
            }
            publisher.close();
        };

        CompletableFuture.supplyAsync(
                () -> pollExecution(executeJobId, req, opts, progressSink),
                executor
        ).whenComplete((resultMap, err) -> {
            try {
                if (err != null) {
                    Throwable cause = unwrap(err);
                    if (cause instanceof QTSCanceledError || resultFuture.isCancelled()) {
                        state.compareAndSet(State.EXECUTING, State.CANCELED);
                    } else {
                        state.compareAndSet(State.EXECUTING, State.FAILED);
                    }
                    resultFuture.completeExceptionally(cause);
                    publisher.closeExceptionally(cause);
                } else {
                    state.compareAndSet(State.EXECUTING, State.COMPLETED);
                    resultFuture.complete(resultMap);
                    publisher.close();
                }
            } catch (Throwable t) {
                log.warn("Job finalization raised", t);
            }
        });

        return new Backtest(executeJobId, strategy, resultFuture, publisher, state, cancelHook);
    }

    private ResultMap pollExecution(
            String executeJobId,
            BacktestRequest req,
            BacktestOptions opts,
            Consumer<BacktestProgress> progressSink) {

        BacktestJobResult finalResult = poll(
                BacktestStage.EXECUTING,
                opts,
                percent -> progressSink.accept(new BacktestProgress(BacktestStage.EXECUTING, percent)),
                () -> call(
                        () -> backtestingApi.getBacktestResult(req.exchangeId(), TICKER, executeJobId),
                        "Execution result request failed",
                        QTSExecutionError::new),
                r -> StatusNormalizer.normalize(statusOf(r)) == Normalized.IN_PROGRESS);

        Normalized norm = StatusNormalizer.normalize(statusOf(finalResult));
        if (norm == Normalized.FAILED) {
            throw new QTSExecutionError(statusDetailOrDefault(finalResult.getState().getStatusDetail(), "Execution failed"));
        }
        if (norm == Normalized.ABORTED) {
            throw new QTSCanceledError("Execution aborted");
        }
        ResultMap results = finalResult.getResults();
        log.info("Execution result for job {}: state={} instrument={} strategyId={} pnl={} trades={}",
                executeJobId,
                statusOf(finalResult),
                results != null ? results.getInstrument() : null,
                results != null ? results.getStrategyId() : null,
                results != null ? results.getPnlTotal() : null,
                results != null ? results.getTotalTrades() : null);
        return results;
    }

    private String compileStrategy(String source) {
        String strategyId = strategyClient.compile(source);
        if (strategyId == null || strategyId.isBlank()) {
            throw new QTSStrategyCompileError("Compile response missing strategyId");
        }
        return strategyId;
    }

    private String prepareData(BacktestRequest req, BacktestOptions opts) {
        PrepareRequest body = new PrepareRequest()
                .instrument(req.instrument())
                .from(req.from())
                .to(req.to());
        return Preparation.prepare(
                backtestingApi, req.exchangeId(), TICKER, body,
                opts.pollInterval(), opts.maxPollInterval(), opts.timeout(),
                percent -> emit(opts.onProgress(), new BacktestProgress(BacktestStage.PREPARING, percent)),
                // Surface the backend's coverage ratio for the prepared window (spec 0.98.0) on the
                // final PREPARING event, so callers can react to a partially-covered range.
                state -> emit(opts.onProgress(),
                        new BacktestProgress(BacktestStage.PREPARING, 100.0, state.getCoverageRatio())));
    }

    private <T> T poll(
            BacktestStage stage,
            BacktestOptions opts,
            Consumer<Double> onPercent,
            Supplier<T> fetch,
            Predicate<T> retryWhile) {

        return Polling.poll(
                stage.toString(),
                opts.pollInterval(), opts.maxPollInterval(), opts.timeout(),
                fetch,
                retryWhile,
                result -> {
                    if (onPercent == null) return;
                    Double percent = extractPercent(result);
                    if (percent != null) onPercent.accept(percent);
                });
    }

    private static Double extractPercent(Object result) {
        if (result instanceof BacktestJobResult bjr && bjr.getState() != null) {
            return Polling.percent(bjr.getState().getSize(), bjr.getState().getCompleted());
        }
        return null;
    }

    private static void emit(Consumer<BacktestProgress> sink, BacktestProgress p) {
        if (sink == null) return;
        try {
            sink.accept(p);
        } catch (RuntimeException e) {
            log.warn("onProgress callback threw", e);
        }
    }

    private static String statusDetailOrDefault(String detail, String fallback) {
        return (detail == null || detail.isBlank()) ? fallback : detail;
    }

    /**
     * Status of an execute-result response, or {@code null} when it carries no state.
     *
     * <p>The API answers {@code 202} with an empty body when a job is known but its result is not
     * readable yet, so {@code getState()} is legitimately null on a successful response. Reading
     * the status through this instead of dereferencing directly is what keeps a 202 a poll:
     * {@link StatusNormalizer#normalize} maps null to {@code IN_PROGRESS}, so the loop asks again
     * under its existing timeout.
     *
     * <p>Dereferencing directly does not fail loudly, which is why this is easy to reintroduce.
     * The {@code NullPointerException} is raised inside Failsafe's result predicate, where it is
     * swallowed: the retry simply does not match, the poll <em>ends</em>, and the caller is handed
     * a null {@code ResultMap} — the same "finished, and I could not find it" trap the 202 exists
     * to prevent, moved to the client side. Verified by reverting this guard: the regression test
     * fails with a null result, not with a visible NPE.
     */
    private static JobState.StatusEnum statusOf(BacktestJobResult result) {
        return result == null || result.getState() == null ? null : result.getState().getStatus();
    }

    private static Throwable unwrap(Throwable t) {
        if (t instanceof java.util.concurrent.CompletionException && t.getCause() != null) {
            return t.getCause();
        }
        return t;
    }

    private static <T, E extends QTSError> T call(
            ApiCalls.ApiCall<T> call,
            String message,
            java.util.function.BiFunction<String, Throwable, E> errorCtor) {
        return ApiCalls.call(call, message, errorCtor);
    }
}
