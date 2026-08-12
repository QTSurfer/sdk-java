package com.qtsurfer.api.sdk;

import com.qtsurfer.api.client.model.SweepProgress;

/**
 * Emitted at stage transitions and after each poll of a running sweep.
 *
 * <p>The {@code snapshot} is where the detail lives, and two of its fields are
 * easy to add together by mistake:
 *
 * <ul>
 *   <li>{@link SweepProgress#getAborted()} counts individual runs that executed
 *       and aborted — a row-level count.</li>
 *   <li>{@link SweepProgress#getFailedShards()} counts whole units of work
 *       (shards, or folds on a walk-forward sweep) that failed and will not be
 *       retried, having never reported anything.</li>
 * </ul>
 *
 * <p>They measure different things: a shard that dies before producing a single
 * row leaves {@code aborted} at zero, which is exactly why the second count
 * exists. Summing them double-counts nothing and describes nothing.
 *
 * <p>{@link SweepProgress#getRetrying()} is not a failure count either — those
 * units failed on something transient and are queued to be attempted again, so
 * a sweep with a non-zero value there is still expected to finish.
 *
 * <p>{@link SweepProgress#getEtaSeconds()} is <em>omitted, never zero</em> when
 * it cannot be computed: a sweep with nothing finished has no observed rate to
 * extrapolate from, and a zero would read as "about to finish". When present it
 * runs conservative — it excludes queue wait entirely, and a sweep that spent
 * part of its life being retried will have diluted the rate it is derived from.
 *
 * @param stage         current workflow stage
 * @param percent       0-100, computed from the runs finished out of the runs expected
 *                      ({@code done}/{@code total} on the snapshot, both run-level counts —
 *                      not the shard counts, which partition units of work rather than runs).
 *                      {@code null} on stage transitions before the first poll
 * @param coverageRatio fraction (0-1) of the requested window that actually holds data, as
 *                      reported once preparation completes. Non-{@code null} only on the final
 *                      {@link BacktestStage#PREPARING} event. Worth reading on a sweep in
 *                      particular: a thinly covered window is about to be scored N times over
 * @param snapshot      the platform's progress record for the sweep. Non-{@code null} only on
 *                      {@link BacktestStage#EXECUTING} events
 */
public record SweepProgressEvent(
        BacktestStage stage,
        Double percent,
        Double coverageRatio,
        SweepProgress snapshot) {

    /**
     * Convenience form for stage transitions and preparation progress;
     * {@code coverageRatio} and {@code snapshot} default to {@code null}.
     *
     * @param stage   current workflow stage
     * @param percent 0-100, or {@code null}
     */
    public SweepProgressEvent(BacktestStage stage, Double percent) {
        this(stage, percent, null, null);
    }
}
