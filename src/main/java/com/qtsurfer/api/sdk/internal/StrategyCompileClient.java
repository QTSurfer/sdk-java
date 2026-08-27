package com.qtsurfer.api.sdk.internal;

import com.qtsurfer.api.sdk.CompiledStrategy;

import java.util.List;

/**
 * Backend-facing client for the strategy compile step. Abstracted so the
 * workflow can be tested without an HTTP backend.
 */
public interface StrategyCompileClient {

    /** Compile strategy source synchronously; returns the resulting strategyId. */
    String compile(String source);

    /** Compile with optional metadata; existing test doubles need only implement {@link #compile(String)}. */
    default CompiledStrategy compileDetails(String source) {
        return new CompiledStrategy(compile(source), List.of());
    }
}
