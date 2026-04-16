package net.qtsurfer.api.sdk;

import net.qtsurfer.api.sdk.workflows.BacktestWorkflow;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * A compiled strategy handle returned by {@link QTSurfer#compile}. Use it to
 * spawn one or more {@link Backtest}s that reuse the same compilation.
 */
public final class Strategy {

    private final String id;
    private final BacktestWorkflow workflow;

    /** Internal constructor used by the SDK workflow; not part of the public contract. */
    public Strategy(String id, BacktestWorkflow workflow) {
        this.id = Objects.requireNonNull(id, "id");
        this.workflow = Objects.requireNonNull(workflow, "workflow");
    }

    /** The compiled strategyId as returned by the backend. */
    public String id() {
        return id;
    }

    /** Run a backtest with this strategy. Prepare + execute start immediately. */
    public CompletableFuture<Backtest> backtest(BacktestRequest request, BacktestOptions options) {
        Objects.requireNonNull(request, "request");
        return workflow.submitExecution(this, request, options != null ? options : BacktestOptions.defaults());
    }

    public CompletableFuture<Backtest> backtest(BacktestRequest request) {
        return backtest(request, BacktestOptions.defaults());
    }

    @Override
    public String toString() {
        return "Strategy[" + id + "]";
    }
}
