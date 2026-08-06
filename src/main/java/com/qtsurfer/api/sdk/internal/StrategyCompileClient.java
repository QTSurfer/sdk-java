package com.qtsurfer.api.sdk.internal;

/**
 * Backend-facing client for the strategy compile step. Abstracted so the
 * workflow can be tested without an HTTP backend.
 */
public interface StrategyCompileClient {

    /** Compile strategy source synchronously; returns the resulting strategyId. */
    String compile(String source);
}
