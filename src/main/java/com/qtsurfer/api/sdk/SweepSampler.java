package com.qtsurfer.api.sdk;

import com.qtsurfer.api.client.model.SweepSpecRequest;

/**
 * How the parameter grid is turned into the list of vectors that actually run.
 *
 * <p>{@link #GRID} is the platform default and runs the full cross product.
 * {@link #RANDOM} and {@link #LHS} draw a bounded number of vectors instead,
 * so both need {@link SweepRequest.Builder#samples(int)}; {@code samples} is
 * ignored by {@link #GRID}.
 */
public enum SweepSampler {

    /** Every combination of every axis value. Cost is the product of the axis sizes. */
    GRID(SweepSpecRequest.SamplerEnum.GRID),

    /** Uniformly random draws from the grid, capped at {@code samples}. */
    RANDOM(SweepSpecRequest.SamplerEnum.RANDOM),

    /** Latin hypercube draws, which spread the sample more evenly than uniform random. */
    LHS(SweepSpecRequest.SamplerEnum.LHS);

    private final SweepSpecRequest.SamplerEnum wire;

    SweepSampler(SweepSpecRequest.SamplerEnum wire) {
        this.wire = wire;
    }

    /**
     * Internal: the underlying api-client enum. Exposed so the SDK's own
     * helpers can pass it through without re-encoding.
     *
     * @return the api-client sampler constant
     */
    public SweepSpecRequest.SamplerEnum wire() {
        return wire;
    }
}
