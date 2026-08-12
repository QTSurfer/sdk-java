package com.qtsurfer.api.sdk;

import com.qtsurfer.api.client.model.ExecuteSweepResult;

/**
 * Which view of a sweep's rows to read: the display leaderboard, or every row
 * in a stable order.
 *
 * <p>The two answer different questions, and the choice decides whether
 * {@link SweepRanking} means anything at all — see {@link #NATURAL}.
 */
public enum SweepOrder {

    /**
     * The display leaderboard: sorted, and capped at a display limit.
     *
     * <p>This is the platform default, and the view {@link SweepRanking}
     * applies to. {@link ExecuteSweepResult#getTruncated()} is {@code true}
     * when the cap actually bit, in which case rows exist that this view does
     * not carry — {@link #NATURAL} is how to reach them.
     */
    RANKED("ranked"),

    /**
     * Every available row, untruncated, in deterministic
     * {@link com.qtsurfer.api.client.model.SweepRunRow#getRunIx() runIx} order.
     *
     * <p>This is the view to read when you are materialising durable trial rows
     * rather than showing a top-N, and the only way to reach rows the ranked
     * view dropped when {@link ExecuteSweepResult#getTruncated()} is
     * {@code true}.
     *
     * <p><strong>{@link SweepRanking} is ignored here.</strong> This view is
     * always ordered by {@code runIx}, so a plateau or raw preference set
     * alongside it has no effect and the response reports {@code raw}. Rank,
     * plateau score and neighbour count belong to the ranked view and are not
     * part of this one.
     */
    NATURAL("natural");

    private final String wire;

    SweepOrder(String wire) {
        this.wire = wire;
    }

    /**
     * Internal: the value sent as the {@code order} query parameter. Exposed so
     * the SDK's own helpers can pass it through without re-encoding.
     *
     * @return the wire token for this view
     */
    public String wire() {
        return wire;
    }
}
