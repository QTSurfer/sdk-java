package net.qtsurfer.api.sdk;

import net.qtsurfer.api.client.model.ResultMap;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Handle for a running backtest execution. Returned by {@link Strategy#backtest}
 * once the execute request has been accepted by the backend.
 *
 * <ul>
 *   <li>{@link #await()} resolves with the final {@link ResultMap} when polling completes.</li>
 *   <li>{@link #progress()} streams {@link BacktestProgress} events through a {@link Flow.Publisher}.</li>
 *   <li>{@link #cancel()} triggers a best-effort server-side {@code cancelExecution}.</li>
 *   <li>{@link #state()} returns a local snapshot of the lifecycle.</li>
 * </ul>
 */
public final class Backtest {

    /** Execution lifecycle as observed by the SDK. */
    public enum State { EXECUTING, COMPLETED, FAILED, CANCELED }

    private final String id;
    private final Strategy strategy;
    private final CompletableFuture<ResultMap> result;
    private final Flow.Publisher<BacktestProgress> progress;
    private final AtomicReference<State> state;
    private final Runnable cancelHook;

    public Backtest(
            String id,
            Strategy strategy,
            CompletableFuture<ResultMap> result,
            Flow.Publisher<BacktestProgress> progress,
            AtomicReference<State> state,
            Runnable cancelHook) {
        this.id = Objects.requireNonNull(id, "id");
        this.strategy = Objects.requireNonNull(strategy, "strategy");
        this.result = Objects.requireNonNull(result, "result");
        this.progress = Objects.requireNonNull(progress, "progress");
        this.state = Objects.requireNonNull(state, "state");
        this.cancelHook = Objects.requireNonNull(cancelHook, "cancelHook");
    }

    /** Server-side execute jobId. */
    public String id() { return id; }

    public Strategy strategy() { return strategy; }

    public State state() { return state.get(); }

    /** Reactive-streams feed of progress events; terminates when the job reaches a terminal state. */
    public Flow.Publisher<BacktestProgress> progress() { return progress; }

    /** Resolves with the final ResultMap. Completes exceptionally on failure or cancellation. */
    public CompletableFuture<ResultMap> await() { return result; }

    /**
     * Request cancellation. Cancels the underlying polling future and best-effort
     * calls {@code cancelExecution} server-side.
     *
     * @return {@code true} if the call caused a transition from {@link State#EXECUTING}
     */
    public boolean cancel() {
        if (!state.compareAndSet(State.EXECUTING, State.CANCELED)) {
            return false;
        }
        cancelHook.run();
        return true;
    }

    @Override
    public String toString() {
        return "Backtest[" + id + ", state=" + state.get() + "]";
    }
}
