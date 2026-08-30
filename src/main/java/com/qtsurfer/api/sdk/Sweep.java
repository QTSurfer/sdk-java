package com.qtsurfer.api.sdk;

import com.qtsurfer.api.client.model.ExecuteSweepAccepted;
import com.qtsurfer.api.client.model.ExecuteSweepResult;
import com.qtsurfer.api.client.model.SweepMarginal;
import com.qtsurfer.api.client.model.SweepRunRow;
import com.qtsurfer.api.client.model.SweepSensitivity;
import com.qtsurfer.api.client.model.WalkForwardResult;
import com.qtsurfer.api.sdk.errors.QTSError;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Handle for a running parameter sweep. Returned by
 * {@link QTSurfer#sweep(SweepRequest)} once the platform has accepted the
 * sweep; the leaderboard keeps being polled in the background.
 *
 * <ul>
 *   <li>{@link #await()} resolves with the final {@link ExecuteSweepResult}.</li>
 *   <li>{@link #results(SweepOrder)} re-reads that leaderboard in another view — the
 *       untruncated one, for instance — without re-running anything.</li>
 *   <li>{@link #progress()} streams {@link SweepProgressEvent}s.</li>
 *   <li>{@link #cancel()} asks the platform to stop between parameter vectors.</li>
 *   <li>{@link #sensitivity()} reads how the objective responds to each axis.</li>
 *   <li>{@link #state()} returns a local snapshot of the lifecycle.</li>
 *   <li>{@link #accepted()} carries what acceptance already answered — the effective
 *       seed, the grid size, whether this submission enqueued anything, and whether
 *       this is a walk-forward sweep.</li>
 * </ul>
 */
public final class Sweep {

    /** Sweep lifecycle as observed by the SDK. */
    public enum State {
        /** Submitted and being polled. */
        EXECUTING,
        /** Finished. The platform's own status may still be {@code PARTIAL}. */
        COMPLETED,
        /** The poll itself failed — transport, HTTP, or a stage timeout. */
        FAILED,
        /** The platform reported the sweep as cancelled. */
        CANCELED
    }

    private final ExecuteSweepAccepted accepted;
    private final String requestId;
    private final String strategyId;
    private final CompletableFuture<ExecuteSweepResult> result;
    private final Flow.Publisher<SweepProgressEvent> progress;
    private final AtomicReference<State> state;
    private final Runnable cancelHook;
    private final BiFunction<SweepOrder, SweepRanking, ExecuteSweepResult> resultsReader;
    private final Function<SweepObjective, SweepSensitivity> sensitivityReader;

    /**
     * Internal constructor used by the SDK workflow; not part of the public contract.
     *
     * @param accepted          the platform's acceptance of the sweep, exactly as it answered
     * @param requestId         the prepared dataset this sweep runs on
     * @param strategyId        the compilation every trial runs
     * @param result            resolves with the final leaderboard
     * @param progress          feed of progress events
     * @param state             lifecycle holder, shared with the polling task
     * @param cancelHook        requests server-side cancellation
     * @param resultsReader     re-reads the leaderboard for an optional order and ranking
     * @param sensitivityReader reads sensitivity surfaces for an optional objective
     */
    public Sweep(
            ExecuteSweepAccepted accepted,
            String requestId,
            String strategyId,
            CompletableFuture<ExecuteSweepResult> result,
            Flow.Publisher<SweepProgressEvent> progress,
            AtomicReference<State> state,
            Runnable cancelHook,
            BiFunction<SweepOrder, SweepRanking, ExecuteSweepResult> resultsReader,
            Function<SweepObjective, SweepSensitivity> sensitivityReader) {
        this.accepted = Objects.requireNonNull(accepted, "accepted");
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.strategyId = Objects.requireNonNull(strategyId, "strategyId");
        this.result = Objects.requireNonNull(result, "result");
        this.progress = Objects.requireNonNull(progress, "progress");
        this.state = Objects.requireNonNull(state, "state");
        this.cancelHook = Objects.requireNonNull(cancelHook, "cancelHook");
        this.resultsReader = Objects.requireNonNull(resultsReader, "resultsReader");
        this.sensitivityReader = Objects.requireNonNull(sensitivityReader, "sensitivityReader");
    }

    /**
     * Server-side sweepId.
     *
     * @return the sweep identifier
     */
    public String id() { return accepted.getSweepId(); }

    /**
     * The prepared dataset every trial ran against — the prepare jobId the
     * workflow resolved before submitting, which is also what addresses this
     * sweep on the wire.
     *
     * <p>This is the value the workflow prepared with, not the acceptance echo
     * of it. {@link #accepted()} carries the echo, unmodified, for anyone who
     * wants to compare the two.
     *
     * @return the prepared-dataset identifier
     */
    public String requestId() { return requestId; }

    /**
     * The compiled strategy every trial shares.
     *
     * @return the strategyId
     */
    public String strategyId() { return strategyId; }

    /**
     * What acceptance already answered, before a single trial has run, exactly
     * as the platform sent it.
     *
     * <p>Three of its fields are the reason this is exposed rather than folded
     * away:
     *
     * <ul>
     *   <li>{@link ExecuteSweepAccepted#getSeed()} — the effective seed, generated
     *       platform-side when the request omitted one. Submitting it again is what
     *       makes a randomly sampled sweep replayable.</li>
     *   <li>{@link ExecuteSweepAccepted#getQueued()} — {@code false} means an identical
     *       sweep already existed and nothing new was enqueued. The handle is still
     *       valid and still resolves; it is just reading a sweep this call did not
     *       start.</li>
     *   <li>{@link ExecuteSweepAccepted#getWalkForward()} — present exactly when this is
     *       a walk-forward sweep. It is the discriminator, and it is available here
     *       immediately, so code watching {@link #progress()} can branch on the answer's
     *       shape without waiting for {@link #await()}.</li>
     * </ul>
     *
     * @return the acceptance record
     */
    public ExecuteSweepAccepted getAccepted() { return accepted; }

    /** @deprecated Use {@link #getAccepted()}. */
    @Deprecated(forRemoval = false)
    public ExecuteSweepAccepted accepted() { return getAccepted(); }

    /**
     * Local snapshot of the sweep lifecycle; does not itself contact the server.
     *
     * @return the current state
     */
    public State getState() { return state.get(); }

    /** @deprecated Use {@link #getState()}. */
    @Deprecated(forRemoval = false)
    public State state() { return getState(); }

    /**
     * Reactive-streams feed of progress events; terminates when the sweep
     * reaches a terminal state.
     *
     * @return the progress publisher
     */
    public Flow.Publisher<SweepProgressEvent> getProgress() { return progress; }

    /** @deprecated Use {@link #getProgress()}. */
    @Deprecated(forRemoval = false)
    public Flow.Publisher<SweepProgressEvent> progress() { return getProgress(); }

    /**
     * Resolves with the final leaderboard once the sweep stops advancing.
     *
     * <p><strong>This resolves on every terminal status, cancellation
     * included</strong> — {@code COMPLETED}, {@code PARTIAL} and
     * {@code CANCELLED} all hand back the result rather than raising. That is a
     * deliberate divergence from {@link Backtest#await()}, which completes
     * exceptionally when its run is aborted: a cancelled sweep keeps every row
     * it already finished, and throwing them away would lose the only reason to
     * cancel a sweep late rather than early. Read
     * {@link ExecuteSweepResult#getStatus()} to find out which of the three you
     * got. The future completes exceptionally only for transport failures, HTTP
     * errors, and stage timeouts.
     *
     * <p>{@code PARTIAL} means at least one unit of work died and its runs are
     * simply missing. There is no failed status for a sweep as a whole, so a
     * sweep whose every shard died is {@code PARTIAL} with an empty leaderboard
     * — check {@link ExecuteSweepResult#getLeaderboardSize()} before reading
     * anything into a top row.
     *
     * <h4>Reading the leaderboard</h4>
     *
     * <p><strong>The default order is not the raw objective order.</strong> It is
     * plateau order, and {@link ExecuteSweepResult#getRanking()} says which was
     * actually applied — not always the one requested, because a sweep with no
     * stored parameter grid cannot be plateau-ranked and falls back to raw. See
     * {@link SweepRanking}.
     *
     * <p><strong>The default view is capped.</strong> When
     * {@link ExecuteSweepResult#getTruncated()} is {@code true}, rows exist that
     * the leaderboard does not carry —
     * {@link ExecuteSweepResult#getLeaderboardSize()} counts what is available.
     * {@link #results(SweepOrder) results(SweepOrder.NATURAL)} returns all of
     * them, in {@code runIx} order and with no ranking applied, by re-reading
     * this same sweep — nothing is re-run.
     *
     * <p><strong>{@link SweepRunRow#getPlateauScore()} and
     * {@link SweepRunRow#getNeighbourCount()} are read together.</strong> A
     * neighbour count of {@code 0} means the point had no neighbours in the grid
     * to compare against, so its plateau score is unevidenced rather than
     * confirmed — on its own it is indistinguishable from a genuinely robust
     * one.
     *
     * <p><strong>{@link SweepRunRow#getDeflatedSharpe()}</strong> is the
     * probability that a row's Sharpe reflects real edge rather than the best
     * draw from however many vectors were tried. Around 0.95 and up it survives
     * the multiple-testing correction; near 0.5 or below it is not
     * distinguishable from the best of a pile of coin flips. It is absent on
     * aborted runs, and on sweeps with too few trials to establish any
     * dispersion to deflate against.
     *
     * <p><strong>{@link ExecuteSweepResult#getPbo()}</strong> is the probability
     * of backtest overfitting for the sweep as a whole: how often the
     * configuration that won in-sample lands below median out-of-sample. Above
     * roughly 0.5 the sweep is selecting noise, and that verdict is about the
     * search, not about any one row — a high value discredits the top row
     * however good it looks. It is computed once the last unit of work
     * finishes, so it is absent while the sweep is still running and on sweeps
     * too small for the statistic to mean anything.
     *
     * <h4>A walk-forward sweep answers in a different shape</h4>
     *
     * <p>{@link ExecuteSweepResult#getWalkForward()} is the discriminator, and
     * it appears as soon as the sweep is accepted — before any fold has
     * finished — so it is safe to branch on while polling (it is also on
     * {@link #accepted()}). When it is present, the leaderboard is one row per
     * <em>completed fold</em>: that fold's winner as it scored out-of-sample,
     * with {@link SweepRunRow#getRunIx()} carrying the fold index rather than a
     * position in the grid. No plateau score, deflated Sharpe or PBO figure is
     * reported for one — the out-of-sample numbers are already the honest
     * measurement.
     *
     * <p><strong>{@link WalkForwardResult#getParamDrift()} absent is not
     * zero.</strong> The field is omitted whenever the figure could not be
     * computed — fewer than two folds finished, or no stored grid to place the
     * winners on — and zero is itself a meaningful reading there (winners that
     * never moved), so a placeholder would be indistinguishable from perfect
     * stability.
     *
     * <p><strong>An empty leaderboard is not always an empty answer.</strong> A
     * sweep can finish having scored nothing — every shard failed before
     * producing a row — and in that case
     * {@link ExecuteSweepResult#getFailReason()} carries the cause reported by
     * the <em>first</em> shard to fail, typically something the whole grid would
     * have hit, such as a strategy that could not be loaded. Read it before
     * concluding that a sweep with no rows simply found nothing: those are
     * different outcomes and the leaderboard alone cannot tell them apart. Only
     * the first failure is recorded, so where several shards failed differently
     * this names one of them rather than summarising all — pair it with
     * {@code getProgress().getFailedShards()} for the count.
     *
     * @return the final leaderboard
     */
    public CompletableFuture<ExecuteSweepResult> await() { return result; }

    /**
     * Re-read this sweep's leaderboard in a different view.
     *
     * <p>Equivalent to {@link #results(SweepOrder, SweepRanking)} with no
     * ranking preference.
     *
     * @param order which view to read; {@code null} takes the platform default
     * @return the leaderboard as it stands now, in the requested view
     * @throws QTSError on HTTP 4xx/5xx or transport failure
     */
    public ExecuteSweepResult getResults(SweepOrder order) {
        return getResults(order, null);
    }

    /**
     * Re-read this sweep's leaderboard in a different view, without re-running
     * anything.
     *
     * <p><strong>This is a read of the same sweep</strong>, addressed by the
     * ids this handle already holds. It does not compile, does not prepare,
     * does not submit, and does not create a second sweep — the view is a query
     * parameter on the read, not a property of the run. {@link #await()}
     * resolves once, in whatever view {@link SweepOptions} fixed at submit
     * time; this is how to look at the same rows another way afterwards.
     *
     * <p><strong>It is the route to rows the ranked view dropped.</strong> When
     * {@link ExecuteSweepResult#getTruncated()} is {@code true} the leaderboard
     * was capped for display; {@link SweepOrder#NATURAL} returns every
     * available row instead, in deterministic {@code runIx} order.
     *
     * <p><strong>{@code ranking} is ignored when {@code order} is
     * {@link SweepOrder#NATURAL}</strong> — that view is always ordered by
     * {@code runIx}, and the response comes back {@code raw} whatever was
     * asked for. Rank, plateau score and neighbour count belong to the ranked
     * view and are not part of it.
     *
     * <p>Works on a sweep still in flight, where it returns the rows finished
     * so far — the same as {@link #sensitivity()}. It is not only for completed
     * sweeps, and it does not wait for one.
     *
     * <p>Synchronous — blocks the calling thread for one HTTP round trip. Like
     * every handle-scoped call, it does not take part in an
     * {@link com.qtsurfer.api.sdk.auth.AuthenticatedClient}'s
     * refresh-on-401 policy.
     *
     * @param order   which view to read; {@code null} takes the platform default
     *                ({@link SweepOrder#RANKED})
     * @param ranking how to order the ranked view; {@code null} takes the platform
     *                default ({@link SweepRanking#PLATEAU})
     * @return the leaderboard as it stands now, in the requested view
     * @throws QTSError on HTTP 4xx/5xx or transport failure
     */
    public ExecuteSweepResult getResults(SweepOrder order, SweepRanking ranking) {
        return resultsReader.apply(order, ranking);
    }

    /** @deprecated Use {@link #getResults(SweepOrder)}. */
    @Deprecated(forRemoval = false)
    public ExecuteSweepResult results(SweepOrder order) { return getResults(order); }

    /** @deprecated Use {@link #getResults(SweepOrder, SweepRanking)}. */
    @Deprecated(forRemoval = false)
    public ExecuteSweepResult results(SweepOrder order, SweepRanking ranking) { return getResults(order, ranking); }

    /**
     * Ask the platform to stop the sweep between parameter vectors.
     *
     * <p>The rows already finished stay readable, so this does not abandon the
     * work done so far: the poll keeps running until the platform reports the
     * sweep as {@code CANCELLED}, and {@link #await()} then resolves normally
     * with the partial leaderboard. Cancelling therefore depends on the
     * platform answering; on a {@link SweepOptions} with no {@code timeout},
     * a sweep that never reports cancelled leaves {@code await()} waiting.
     *
     * <p>A cancel that arrives after the last unit of work has finished changes
     * nothing — the sweep completes and {@link #state()} settles on
     * {@link State#COMPLETED}.
     *
     * @return {@code true} if the call caused a transition from {@link State#EXECUTING}
     */
    public boolean cancel() {
        if (!state.compareAndSet(State.EXECUTING, State.CANCELED)) {
            return false;
        }
        cancelHook.run();
        return true;
    }

    /**
     * Sensitivity surfaces for the objective this sweep was submitted with.
     *
     * @return how the objective responds to each axis
     * @throws QTSError on HTTP 4xx/5xx or transport failure
     */
    public SweepSensitivity getSensitivity() {
        return getSensitivity(null);
    }

    /**
     * How the objective moves as each parameter moves — the question a
     * leaderboard cannot answer. A leaderboard says which point won; a sweep
     * can spend its whole budget on an axis that never moved the objective at
     * all, and the top rows hide that completely.
     *
     * <p>A <em>marginal</em> takes one axis and collapses every other one: for
     * each value of that axis it aggregates every run that used it, whatever
     * the rest of the parameters were. A flat marginal means the axis did not
     * matter over the range swept. {@code best}, {@code mean} and {@code worst}
     * are all reported because them disagreeing is the signal — a value with a
     * high best and a poor mean only works in specific company, which is an
     * interaction, and a single number would hide it. A <em>heatmap</em> does
     * the same over a pair of axes, where that interaction is visible directly.
     *
     * <p><strong>Check
     * {@link SweepSensitivity#getHeatmapsTruncated()}.</strong> Marginals are
     * always complete; the pair surfaces are quadratic in the axis count and
     * may be capped to stay inside the response budget. When the flag is
     * {@code true}, at least one pair was left out, so the list you have is not
     * the full set of interactions. This method hands back the whole
     * {@link SweepSensitivity} rather than just its surfaces precisely so that
     * flag cannot be lost on the way out.
     *
     * <p>Readable while the sweep is still running, in which case the
     * aggregates describe the runs finished so far and
     * {@link SweepSensitivity#getRowsAnalysed()} says how many that was.
     * Aborted runs are excluded throughout: a run that threw measured nothing,
     * and counting it as a bad outcome would invent evidence against a
     * parameter value that was never really tested.
     *
     * <p>Synchronous — blocks the calling thread for one HTTP round trip. Like
     * every handle-scoped call, it does not take part in an
     * {@link com.qtsurfer.api.sdk.auth.AuthenticatedClient}'s
     * refresh-on-401 policy.
     *
     * @param objective which metric to aggregate; {@code null} uses the objective the
     *                  sweep was submitted with
     * @return the marginals, the heatmaps, and whether the heatmaps are all of them
     * @throws QTSError on HTTP 4xx/5xx or transport failure
     * @see SweepMarginal
     */
    public SweepSensitivity getSensitivity(SweepObjective objective) {
        return sensitivityReader.apply(objective);
    }

    /** @deprecated Use {@link #getSensitivity()}. */
    @Deprecated(forRemoval = false)
    public SweepSensitivity sensitivity() { return getSensitivity(); }

    /** @deprecated Use {@link #getSensitivity(SweepObjective)}. */
    @Deprecated(forRemoval = false)
    public SweepSensitivity sensitivity(SweepObjective objective) { return getSensitivity(objective); }

    /**
     * Compact debug representation including the sweep id and current state.
     *
     * @return a short description of this handle
     */
    @Override
    public String toString() {
        return "Sweep[" + id() + ", state=" + state.get() + "]";
    }
}
