package com.qtsurfer.api.sdk;

import com.qtsurfer.api.sdk.workflows.BacktestWorkflow;
import com.qtsurfer.api.client.model.DeclaredProperty;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * A compiled strategy handle returned by {@link QTSurfer#compile}. Use it to
 * spawn one or more {@link Backtest}s that reuse the same compilation.
 */
public final class Strategy {

    private final String id;
    private final List<DeclaredProperty> declaredProperties;
    private final List<String> requires;
    private final String name;
    private final String language;
    private final BacktestWorkflow workflow;

    /** Internal constructor used by the SDK workflow; not part of the public contract. */
    public Strategy(String id, BacktestWorkflow workflow) {
        this(id, List.of(), List.of(), workflow);
    }

    /** Internal constructor used by the SDK workflow; not part of the public contract. */
    public Strategy(String id, List<DeclaredProperty> declaredProperties, BacktestWorkflow workflow) {
        this(id, declaredProperties, List.of(), workflow);
    }

    /** Internal constructor used by the SDK workflow; not part of the public contract. */
    public Strategy(String id, List<DeclaredProperty> declaredProperties, List<String> requires, BacktestWorkflow workflow) {
        this(new CompiledStrategy(id, declaredProperties, requires), workflow);
    }

    /** Internal constructor used by the SDK workflow. */
    public Strategy(CompiledStrategy compiled, BacktestWorkflow workflow) {
        this.id = Objects.requireNonNull(compiled.id(), "id");
        this.declaredProperties = compiled.declaredProperties();
        this.requires = compiled.requires();
        this.name = compiled.name();
        this.language = compiled.language();
        this.workflow = Objects.requireNonNull(workflow, "workflow");
    }

    /** The compiled strategyId as returned by the backend. */
    public String id() {
        return id;
    }

    /** Best-effort property vocabulary discovered while compiling this strategy. */
    public List<DeclaredProperty> declaredProperties() { return declaredProperties; }

    /** Capabilities required by this compiled strategy, such as {@code indicators.pro}. */
    public List<String> requires() { return requires; }

    /** The name declared by the strategy source, or null when absent. */
    public String name() { return name; }

    /** The source language, such as java or qtscript, or null when absent. */
    public String language() { return language; }

    /** Run a backtest with this strategy. Prepare + execute start immediately. */
    public CompletableFuture<Backtest> executeBacktest(BacktestRequest request, BacktestOptions options) {
        Objects.requireNonNull(request, "request");
        return workflow.submitExecution(this, request, options != null ? options : BacktestOptions.defaults());
    }

    /** Equivalent to {@link #executeBacktest(BacktestRequest, BacktestOptions)} with {@link BacktestOptions#defaults()}. */
    public CompletableFuture<Backtest> executeBacktest(BacktestRequest request) {
        return executeBacktest(request, BacktestOptions.defaults());
    }

    /** @deprecated Use {@link #executeBacktest(BacktestRequest, BacktestOptions)}. */
    @Deprecated(forRemoval = false)
    public CompletableFuture<Backtest> backtest(BacktestRequest request, BacktestOptions options) {
        return executeBacktest(request, options);
    }

    /** @deprecated Use {@link #executeBacktest(BacktestRequest)}. */
    @Deprecated(forRemoval = false)
    public CompletableFuture<Backtest> backtest(BacktestRequest request) { return executeBacktest(request); }

    /** Compact debug representation including the compiled strategy id. */
    @Override
    public String toString() {
        return "Strategy[" + id + "]";
    }
}
