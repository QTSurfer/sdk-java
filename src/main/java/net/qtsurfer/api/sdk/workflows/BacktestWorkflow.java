package net.qtsurfer.api.sdk.workflows;

import dev.failsafe.FailsafeExecutor;
import dev.failsafe.TimeoutExceededException;
import net.qtsurfer.api.client.api.BacktestingApi;
import net.qtsurfer.api.client.invoker.ApiException;
import net.qtsurfer.api.client.model.AcceptedJob;
import net.qtsurfer.api.client.model.BacktestJobResult;
import net.qtsurfer.api.client.model.DataSourceType;
import net.qtsurfer.api.client.model.ExecuteBacktestingRequest;
import net.qtsurfer.api.client.model.JobState;
import net.qtsurfer.api.client.model.PrepareBacktestingRequest;
import net.qtsurfer.api.client.model.ResultMap;
import net.qtsurfer.api.sdk.Backtest;
import net.qtsurfer.api.sdk.Backtest.State;
import net.qtsurfer.api.sdk.BacktestOptions;
import net.qtsurfer.api.sdk.BacktestProgress;
import net.qtsurfer.api.sdk.BacktestRequest;
import net.qtsurfer.api.sdk.BacktestStage;
import net.qtsurfer.api.sdk.Strategy;
import net.qtsurfer.api.sdk.errors.QTSCanceledError;
import net.qtsurfer.api.sdk.errors.QTSError;
import net.qtsurfer.api.sdk.errors.QTSExecutionError;
import net.qtsurfer.api.sdk.errors.QTSPreparationError;
import net.qtsurfer.api.sdk.errors.QTSStrategyCompileError;
import net.qtsurfer.api.sdk.errors.QTSTimeoutError;
import net.qtsurfer.api.sdk.internal.CompileStatus;
import net.qtsurfer.api.sdk.internal.Policies;
import net.qtsurfer.api.sdk.internal.StatusNormalizer;
import net.qtsurfer.api.sdk.internal.StatusNormalizer.Normalized;
import net.qtsurfer.api.sdk.internal.StrategyCompileClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Orchestrates compile → prepare → execute over {@code net.qtsurfer:api-client}.
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

    /** Async compile: submit with X-Compile-Async then poll until a strategyId is available. */
    public CompletableFuture<Strategy> compile(String source, BacktestOptions opts) {
        BacktestOptions safeOpts = opts != null ? opts : BacktestOptions.defaults();
        return CompletableFuture.supplyAsync(() -> {
            emit(safeOpts.onProgress(), new BacktestProgress(BacktestStage.COMPILING, null));
            String strategyId = compileStrategy(source, safeOpts);
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
                    () -> backtestingApi.executeBacktesting(req.exchangeId(), TICKER, buildExecuteBody(req, strategy, prepareJobId)),
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

    private ExecuteBacktestingRequest buildExecuteBody(
            BacktestRequest req, Strategy strategy, String prepareJobId) {
        ExecuteBacktestingRequest body = new ExecuteBacktestingRequest()
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
                backtestingApi.cancelExecution(req.exchangeId(), TICKER, executeJobId);
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
                        () -> backtestingApi.getExecutionResult(req.exchangeId(), TICKER, executeJobId),
                        "Execution result request failed",
                        QTSExecutionError::new),
                r -> StatusNormalizer.normalize(r.getState().getStatus()) == Normalized.IN_PROGRESS);

        Normalized norm = StatusNormalizer.normalize(finalResult.getState().getStatus());
        if (norm == Normalized.FAILED) {
            throw new QTSExecutionError(statusDetailOrDefault(finalResult.getState().getStatusDetail(), "Execution failed"));
        }
        if (norm == Normalized.ABORTED) {
            throw new QTSCanceledError("Execution aborted");
        }
        return finalResult.getResults();
    }

    private String compileStrategy(String source, BacktestOptions opts) {
        String compileJobId = strategyClient.submit(source);
        if (compileJobId == null || compileJobId.isBlank()) {
            throw new QTSStrategyCompileError("Compile submit response missing jobId");
        }

        CompileStatus status = poll(
                BacktestStage.COMPILING,
                opts,
                null,
                () -> strategyClient.status(compileJobId),
                r -> r.status() == Normalized.IN_PROGRESS);

        if (status.status() == Normalized.FAILED) {
            throw new QTSStrategyCompileError(statusDetailOrDefault(status.statusDetail(), "Strategy compilation failed"));
        }
        if (status.status() == Normalized.ABORTED) {
            throw new QTSCanceledError("Strategy compilation aborted");
        }
        if (status.strategyId() == null || status.strategyId().isBlank()) {
            throw new QTSStrategyCompileError("Compile completed without a strategyId");
        }
        return status.strategyId();
    }

    private String prepareData(BacktestRequest req, BacktestOptions opts) {
        PrepareBacktestingRequest body = new PrepareBacktestingRequest()
                .instrument(req.instrument())
                .from(req.from())
                .to(req.to());
        AcceptedJob accepted = call(
                () -> backtestingApi.prepareBacktesting(req.exchangeId(), TICKER, body),
                "Prepare submission failed",
                QTSPreparationError::new);
        if (accepted == null || accepted.getJobId() == null) {
            throw new QTSPreparationError("Missing jobId in prepare response");
        }
        String prepareJobId = accepted.getJobId();

        JobState state = poll(
                BacktestStage.PREPARING,
                opts,
                percent -> emit(opts.onProgress(), new BacktestProgress(BacktestStage.PREPARING, percent)),
                () -> call(
                        () -> backtestingApi.getPreparationStatus(req.exchangeId(), TICKER, prepareJobId),
                        "Preparation status request failed",
                        QTSPreparationError::new),
                r -> StatusNormalizer.normalize(r.getStatus()) == Normalized.IN_PROGRESS);

        Normalized norm = StatusNormalizer.normalize(state.getStatus());
        if (norm == Normalized.FAILED) {
            throw new QTSPreparationError(statusDetailOrDefault(state.getStatusDetail(), "Data preparation failed"));
        }
        if (norm == Normalized.ABORTED) {
            throw new QTSCanceledError("Data preparation aborted");
        }
        return prepareJobId;
    }

    private <T> T poll(
            BacktestStage stage,
            BacktestOptions opts,
            Consumer<Double> onPercent,
            Supplier<T> fetch,
            Predicate<T> retryWhile) {

        FailsafeExecutor<T> failsafe = Policies.stagePoller(
                opts.pollInterval(), opts.maxPollInterval(), opts.timeout(), retryWhile);

        Supplier<T> wrapped = () -> {
            if (Thread.currentThread().isInterrupted()) {
                throw new QTSCanceledError("Workflow aborted");
            }
            T result = fetch.get();
            if (onPercent != null) {
                Double percent = extractPercent(result);
                if (percent != null) onPercent.accept(percent);
            }
            return result;
        };

        try {
            return failsafe.get(wrapped::get);
        } catch (TimeoutExceededException ex) {
            throw new QTSTimeoutError("Stage " + stage + " exceeded " + opts.timeout(), ex);
        } catch (CancellationException | dev.failsafe.FailsafeException ex) {
            if (Thread.currentThread().isInterrupted()) {
                throw new QTSCanceledError("Workflow aborted", ex);
            }
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            if (cause instanceof QTSError qts) throw qts;
            throw new QTSError("Poll failed: " + cause.getMessage(), cause);
        }
    }

    private static Double extractPercent(Object result) {
        if (result instanceof JobState js) return computePercent(js);
        if (result instanceof BacktestJobResult bjr) return computePercent(bjr.getState());
        return null;
    }

    private static Double computePercent(JobState state) {
        if (state == null || state.getSize() == null || state.getCompleted() == null) return null;
        int size = state.getSize();
        if (size <= 0) return null;
        return (state.getCompleted().doubleValue() / size) * 100.0;
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

    private static Throwable unwrap(Throwable t) {
        if (t instanceof java.util.concurrent.CompletionException && t.getCause() != null) {
            return t.getCause();
        }
        return t;
    }

    @FunctionalInterface
    private interface ApiCall<T> {
        T invoke() throws ApiException;
    }

    private static <T, E extends QTSError> T call(
            ApiCall<T> call,
            String message,
            java.util.function.BiFunction<String, Throwable, E> errorCtor) {
        try {
            return call.invoke();
        } catch (ApiException e) {
            throw errorCtor.apply(message + ": " + describeApiException(e), e);
        }
    }

    private static String describeApiException(ApiException e) {
        if (e.getResponseBody() != null && !e.getResponseBody().isBlank()) {
            return "HTTP " + e.getCode() + " — " + e.getResponseBody();
        }
        return "HTTP " + e.getCode();
    }
}
