package com.qtsurfer.api.sdk.internal;

import com.qtsurfer.api.client.model.BacktestJobResult;
import com.qtsurfer.api.client.model.JobState;
import com.qtsurfer.api.client.model.ResultMap;
import com.qtsurfer.api.sdk.BacktestOutcome;
import com.qtsurfer.api.sdk.internal.StatusNormalizer.Normalized;

/**
 * Reads an execute-result response as one of the four answers a standalone
 * read can get.
 *
 * <p>The status is normalized rather than switched on raw, so this classifies
 * a run by exactly the rule the poll loop stops on — one vocabulary for "what
 * does this status mean", not two that can drift apart.
 *
 * <p>That also makes the {@code null}-safety line up on its own.
 * {@link StatusNormalizer#normalize} maps a missing status to
 * {@code IN_PROGRESS}, which is the one variant that tolerates a {@code null}
 * state, so a body-less response cannot reach a terminal variant and trip its
 * null check.
 */
public final class BacktestOutcomes {

    private BacktestOutcomes() {}

    /**
     * Classify an execute-result response.
     *
     * @param response the api-client response, or {@code null} when the
     *                 platform answered a known job without a body
     * @return the answer the response describes
     */
    public static BacktestOutcome of(BacktestJobResult response) {
        JobState state = response == null ? null : response.getState();
        ResultMap results = response == null ? null : response.getResults();
        Normalized status = StatusNormalizer.normalize(state == null ? null : state.getStatus());
        return switch (status) {
            case COMPLETED -> new BacktestOutcome.Completed(state, results);
            case FAILED -> new BacktestOutcome.Failed(state, results);
            case ABORTED -> new BacktestOutcome.Aborted(state, results);
            case IN_PROGRESS -> new BacktestOutcome.InProgress(state, results);
        };
    }
}
