package net.qtsurfer.api.sdk.internal;

/**
 * Backend-facing client for the two-step strategy compile flow
 * (submit in async mode + poll status). Abstracted so the workflow can be
 * tested without an HTTP backend.
 */
public interface StrategyCompileClient {

    /** Submit strategy source with {@code X-Compile-Async: true}; returns the compile jobId. */
    String submit(String source);

    /** Fetch the current compile status for a given jobId. */
    CompileStatus status(String jobId);
}
