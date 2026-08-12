package com.qtsurfer.api.sdk;

import com.qtsurfer.api.client.model.ExecuteSweepResult;

/**
 * How the leaderboard is ordered.
 *
 * <p>The server default is {@link #PLATEAU}, so the order a sweep answers with
 * is <em>not</em> the raw objective order unless you ask for it. Set it through
 * {@link SweepOptions.Builder#ranking(SweepRanking)}; leaving it unset sends no
 * {@code ranking} parameter at all and takes whatever the platform defaults to.
 *
 * <p><strong>What you request is not always what you get.</strong> Read
 * {@link ExecuteSweepResult#getRanking()} to find out which ordering was
 * actually applied: a sweep with no stored parameter grid has no neighbourhood
 * to score against and falls back to {@link #RAW}, and a walk-forward sweep is
 * always {@link #RAW} because its leaderboard is one out-of-sample row per fold
 * rather than a grid.
 *
 * <p>This applies to the ranked view only. Alongside
 * {@link SweepOrder#NATURAL} it is <strong>ignored</strong> — that view is
 * always ordered by {@code runIx}.
 */
public enum SweepRanking {

    /**
     * Order by plateau score: the objective of the worst run in a point's
     * immediate neighbourhood, so a point only ranks well when the region
     * around it does too.
     *
     * <p>This exists because the highest raw score is very often a spike that
     * does not survive the parameters moving slightly. Rows ranked this way
     * carry {@code plateauScore} and {@code neighbourCount}, and those two are
     * read together — see {@link Sweep#await()}.
     */
    PLATEAU("plateau"),

    /** Order by the objective alone, with no neighbourhood adjustment. */
    RAW("raw");

    private final String wire;

    SweepRanking(String wire) {
        this.wire = wire;
    }

    /**
     * Internal: the value sent as the {@code ranking} query parameter. Exposed
     * so the SDK's own helpers can pass it through without re-encoding.
     *
     * @return the wire token for this ordering
     */
    public String wire() {
        return wire;
    }
}
