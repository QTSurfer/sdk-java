package com.qtsurfer.api.sdk;

import com.qtsurfer.api.client.model.StrategyState;

import java.util.Objects;

/**
 * What a call to validate a strategy <em>did</em> — which is a different
 * question from what the platform currently thinks of the strategy.
 *
 * <p>Validation is idempotent, and the platform answers a request in one of
 * two ways: it queues a check, or it queues nothing because one is already
 * accounted for. Those two answers are what this type separates:
 *
 * <ul>
 *   <li>{@link Queued} — this call started a check.</li>
 *   <li>{@link NotQueued} — this call started nothing, and carries the
 *       {@link StrategyState} the platform holds right now.</li>
 * </ul>
 *
 * <p><strong>{@code NotQueued} does not mean a verdict exists.</strong> The
 * state it carries can itself be {@code pending}: a check queued by an
 * earlier call — possibly from another process — that has not answered yet.
 * The two questions are independent, and a caller usually needs both:
 *
 * <ul>
 *   <li>{@link #queued()} answers <em>did this call start work?</em></li>
 *   <li>{@link StrategyState#getValidation()} answers <em>is there a verdict
 *       right now?</em></li>
 * </ul>
 *
 * <p>Either way, a caller that wants a verdict has to read one: poll
 * {@link QTSurfer#strategyState(String)} until {@code validation} leaves
 * {@code pending}, bounded by a deadline of the caller's own — see
 * {@link QTSurfer#validateStrategy(String)} for why that deadline is not
 * optional and for what a verdict is worth once it arrives.
 *
 * <p>The links above point at {@link QTSurfer} for concreteness;
 * {@link com.qtsurfer.api.sdk.auth.AuthenticatedClient} carries the same pair
 * of methods with the same semantics, and this type is what both return.
 */
public sealed interface ValidationOutcome {

    /**
     * Whether this call started a new check. {@code false} says only that
     * nothing was started — not that a verdict exists.
     *
     * @return {@code true} for {@link Queued}, {@code false} for
     *         {@link NotQueued}
     */
    boolean queued();

    /**
     * Id of the strategy this outcome is about.
     *
     * @return the strategy id
     */
    String strategyId();

    /**
     * This call queued a check, which has not answered yet. There is nothing
     * to read here beyond the strategy id: poll
     * {@link QTSurfer#strategyState(String)} for the verdict.
     *
     * <p>The id is the one the caller passed in, not one read back off the
     * response body.
     *
     * @param strategyId the strategy whose check was queued
     */
    record Queued(String strategyId) implements ValidationOutcome {

        /**
         * @throws NullPointerException if {@code strategyId} is {@code null}
         */
        public Queued {
            Objects.requireNonNull(strategyId, "strategyId");
        }

        /** {@return {@code true} — this call queued a check} */
        @Override
        public boolean queued() {
            return true;
        }
    }

    /**
     * This call queued nothing, because the platform already accounts for
     * the current compilation. {@link #state()} is what it holds right now,
     * which may be a recorded verdict ({@code passed} / {@code failed}) or
     * an outstanding check ({@code pending}) queued by an earlier call.
     *
     * @param state the platform's record of the strategy, never {@code null}
     */
    record NotQueued(StrategyState state) implements ValidationOutcome {

        /**
         * @throws NullPointerException if {@code state} is {@code null}
         */
        public NotQueued {
            Objects.requireNonNull(state, "state");
        }

        /** {@return {@code false} — this call queued nothing} */
        @Override
        public boolean queued() {
            return false;
        }

        /** {@return the strategy id carried by the state} */
        @Override
        public String strategyId() {
            return state.getStrategyId();
        }
    }
}
