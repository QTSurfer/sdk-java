package com.qtsurfer.api.sdk;

/**
 * Emitted at stage transitions and after each poll with known size.
 *
 * @param stage         current workflow stage
 * @param percent       0-100 when the job exposes size information; {@code null} for stage
 *                      transitions before the first poll.
 * @param coverageRatio fraction (0-1) of the requested prepare window that actually holds data,
 *                      as reported by the backend once preparation completes. Non-{@code null}
 *                      only on the final {@link BacktestStage#PREPARING} event; {@code null}
 *                      otherwise.
 */
public record BacktestProgress(BacktestStage stage, Double percent, Double coverageRatio) {

    /** Convenience form without coverage; {@code coverageRatio} defaults to {@code null}. */
    public BacktestProgress(BacktestStage stage, Double percent) {
        this(stage, percent, null);
    }
}
