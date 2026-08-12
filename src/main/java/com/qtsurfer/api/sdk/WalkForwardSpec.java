package com.qtsurfer.api.sdk;

import com.qtsurfer.api.client.model.ExecuteSweepResult;
import com.qtsurfer.api.client.model.WalkForwardResult;

/**
 * Opt a sweep into walk-forward validation.
 *
 * <p>Attaching this changes what the sweep does, not just how much of it runs.
 * Instead of scoring every parameter vector once over the whole range, the data
 * is cut into sequential folds; each fold optimizes the whole grid on its own
 * window and then scores only its winner on the window immediately after —
 * data that winner was never chosen on. The question it answers is not "which
 * parameters won" but "does re-optimizing this periodically actually work".
 *
 * <p>Omit it and nothing about the sweep changes, including the shape of the
 * response.
 *
 * <p><strong>It costs folds × grid.</strong> Four folds over a 500-point grid
 * is roughly 2000 backtests where the plain sweep is 500, which is why it is
 * opt-in. The platform rejects the request outright when that product exceeds
 * its sweep budget.
 *
 * <p><strong>It is a different sweep, not a variant of one.</strong> Two
 * requests that differ only in this block do not deduplicate against each
 * other.
 *
 * <p>The answer arrives as {@link ExecuteSweepResult#getWalkForward()} — see
 * {@link WalkForwardResult} and {@link Sweep#await()} for how to read it.
 *
 * @param folds        how many sequential optimize-then-score windows to run. Two is the
 *                     floor and the reason is structural rather than a tuning preference:
 *                     parameter drift is measured between consecutive fold winners, and a
 *                     single fold has no consecutive pair, so it would report the strongest
 *                     possible stability having measured nothing. The ceiling is a platform
 *                     setting; exceeding it is rejected.
 * @param inSamplePct  share of each fold's window spent optimizing, the rest being where
 *                     its winner is scored. {@code null} takes the platform default. Lower
 *                     values leave more data to score on and, on short sessions, are what
 *                     let the requested fold count tile the data at all.
 */
public record WalkForwardSpec(int folds, Integer inSamplePct) {

    /** @throws IllegalArgumentException when {@code folds} is below 2 or {@code inSamplePct} is out of range */
    public WalkForwardSpec {
        if (folds < 2) {
            throw new IllegalArgumentException("folds must be >= 2, was " + folds);
        }
        if (inSamplePct != null && (inSamplePct < 10 || inSamplePct > 90)) {
            throw new IllegalArgumentException("inSamplePct must be within 10..90, was " + inSamplePct);
        }
    }

    /**
     * Walk forward over the given number of folds, leaving the in-sample share
     * to the platform default.
     *
     * @param folds how many sequential optimize-then-score windows to run; at least 2
     * @return the spec
     */
    public static WalkForwardSpec of(int folds) {
        return new WalkForwardSpec(folds, null);
    }

    /**
     * Walk forward over the given number of folds with an explicit in-sample share.
     *
     * @param folds       how many sequential optimize-then-score windows to run; at least 2
     * @param inSamplePct share of each fold spent optimizing, within 10..90
     * @return the spec
     */
    public static WalkForwardSpec of(int folds, int inSamplePct) {
        return new WalkForwardSpec(folds, inSamplePct);
    }
}
