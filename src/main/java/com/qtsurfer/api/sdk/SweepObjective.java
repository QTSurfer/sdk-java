package com.qtsurfer.api.sdk;

/**
 * The metric a sweep optimizes, and the one its leaderboard and its
 * sensitivity surfaces are read against.
 *
 * <p>One vocabulary throughout: the objective a {@link SweepRequest} is
 * submitted with is the objective the leaderboard is ranked by, and the one
 * {@link Sweep#sensitivity()} aggregates unless a different one is asked for.
 */
public enum SweepObjective {

    /** Risk-adjusted return; the platform default when a request names none. */
    SHARPE("sharpe"),
    /** Downside-risk-adjusted return. */
    SORTINO("sortino"),
    /** Absolute net profit and loss. */
    PNL("pnl"),
    /** Maximum drawdown. */
    MAXDD("maxdd");

    private final String wire;

    SweepObjective(String wire) {
        this.wire = wire;
    }

    /**
     * Internal: the value sent on the wire. Exposed so the SDK's own helpers
     * can pass it through without re-encoding.
     *
     * @return the wire token for this objective
     */
    public String wire() {
        return wire;
    }
}
