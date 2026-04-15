package net.qtsurfer.api.sdk;

/**
 * Emitted at stage transitions and after each poll with known size.
 *
 * @param stage   current workflow stage
 * @param percent 0-100 when the job exposes size information; {@code null} for stage transitions
 *                before the first poll.
 */
public record BacktestProgress(BacktestStage stage, Double percent) {
}
