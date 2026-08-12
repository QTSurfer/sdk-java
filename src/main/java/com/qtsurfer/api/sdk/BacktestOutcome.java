package com.qtsurfer.api.sdk;

import com.qtsurfer.api.client.model.JobState;
import com.qtsurfer.api.client.model.ResultMap;

import java.util.Objects;

/**
 * What the platform has to say about a backtest run right now — read by
 * {@link QTSurfer#backtestResult(String, String)} for a job the calling
 * process did not necessarily start.
 *
 * <p>Four answers, and they are not invented here: they are the four cases the
 * SDK's own poll loop already reasons in when it decides whether a run is
 * still moving. Reading a run without running it has to keep them apart, so
 * they are the variants of this type:
 *
 * <ul>
 *   <li>{@link Completed} — the run finished and its numbers are final.</li>
 *   <li>{@link Failed} — the run finished badly. A real answer, not an
 *       error.</li>
 *   <li>{@link Aborted} — the run was cancelled. Also a real answer.</li>
 *   <li>{@link InProgress} — the platform knows the job but has nothing final
 *       to report yet.</li>
 * </ul>
 *
 * <p><strong>Why a sealed type rather than the results alone.</strong> The
 * workflow path — {@link Backtest#await()} and the {@code backtest(...)}
 * shortcut — hands back a bare {@link ResultMap} because it reports the two
 * bad endings out of band, completing exceptionally with
 * {@link com.qtsurfer.api.sdk.errors.QTSExecutionError} or
 * {@link com.qtsurfer.api.sdk.errors.QTSCanceledError}. A standalone read
 * cannot borrow that channel: asking "what happened to this job?" and being
 * told "it failed" is the question being answered, not the read going wrong.
 * With the exception channel gone, the status has to travel in the return
 * value or it does not travel at all — and the platform always sends it, so
 * dropping it would be discarding the answer.
 *
 * <p>Matching on the variants is the intended use:
 *
 * <pre>{@code
 * BacktestOutcome outcome = qts.backtestResult("binance", jobId);
 *
 * if (outcome instanceof BacktestOutcome.Completed c) {
 *     report(c.results().getPnlTotal());
 * } else if (outcome instanceof BacktestOutcome.Failed f) {
 *     report("failed: " + f.state().getStatusDetail());
 * } else if (outcome instanceof BacktestOutcome.Aborted) {
 *     report("cancelled");
 * } else {
 *     report("still running; ask again later");
 * }
 * }</pre>
 *
 * <p>This library targets Java 17, where a {@code switch} over the variants is
 * still a preview feature — hence the {@code instanceof} chain. On a newer
 * runtime the sealing makes that {@code switch} exhaustive without a default.
 *
 * <p>A job id the platform does not recognise is not one of these. That is the
 * one answer that stays an exception — see
 * {@link QTSurfer#backtestResult(String, String)}.
 *
 * <p>The links above point at {@link QTSurfer} for concreteness;
 * {@link com.qtsurfer.api.sdk.auth.AuthenticatedClient} carries the same
 * method with the same semantics, and this type is what both return.
 */
public sealed interface BacktestOutcome {

    /**
     * The platform's job record, carrying the raw status, the progress counts,
     * and — on a run that ended badly — {@link JobState#getStatusDetail()}.
     *
     * <p>Non-{@code null} on every terminal variant — a run cannot be reported
     * as finished, failed or cancelled without the platform having said so.
     * {@link InProgress} is the only variant where it may be absent, and it is
     * absent for one reason: the platform answered without a body, which is
     * how it reports a job it knows but cannot yet describe. An
     * {@link InProgress} that came from an unrecognised or missing status on a
     * response that <em>did</em> carry a body has one. Either way, read it
     * defensively on that variant.
     *
     * @return the job record; may be {@code null} on {@link InProgress}
     */
    JobState state();

    /**
     * The run's numbers, exactly as the platform sent them.
     *
     * <p><strong>Only final on {@link Completed}.</strong> The field is
     * carried on every variant rather than dropped, because the platform
     * always sends it and this SDK is not the right place to decide it is
     * uninteresting — but a run that has not finished, or that ended badly,
     * has at best a partial account of itself here, and it can be absent
     * entirely.
     *
     * @return the result map, or {@code null} when the response carried none
     */
    ResultMap results();

    /**
     * Whether the run has stopped moving. {@code true} for
     * {@link Completed}, {@link Failed} and {@link Aborted} alike — finishing
     * badly is still finishing, and re-reading will not change the answer.
     *
     * @return {@code true} unless this is {@link InProgress}
     */
    boolean finished();

    /**
     * The run finished and produced its numbers. This is the only variant on
     * which {@link #results()} is a final account of the run.
     *
     * @param state   the platform's job record, never {@code null}
     * @param results the run's numbers
     */
    record Completed(JobState state, ResultMap results) implements BacktestOutcome {

        /**
         * @throws NullPointerException if {@code state} is {@code null}
         */
        public Completed {
            Objects.requireNonNull(state, "state");
        }

        /** {@return {@code true} — the run finished} */
        @Override
        public boolean finished() {
            return true;
        }
    }

    /**
     * The run finished badly. Reading this is not an error: it is the answer
     * to what happened to the job, and re-reading will keep saying the same
     * thing.
     *
     * <p>{@link JobState#getStatusDetail()} carries the platform's account of
     * why, when it recorded one.
     *
     * @param state   the platform's job record, never {@code null}
     * @param results whatever the run managed to produce before it failed,
     *                which may be nothing
     */
    record Failed(JobState state, ResultMap results) implements BacktestOutcome {

        /**
         * @throws NullPointerException if {@code state} is {@code null}
         */
        public Failed {
            Objects.requireNonNull(state, "state");
        }

        /** {@return {@code true} — the run finished, badly} */
        @Override
        public boolean finished() {
            return true;
        }
    }

    /**
     * The run was cancelled. Like {@link Failed}, a real answer rather than an
     * error, and equally terminal.
     *
     * @param state   the platform's job record, never {@code null}
     * @param results whatever the run managed to produce before it stopped,
     *                which may be nothing
     */
    record Aborted(JobState state, ResultMap results) implements BacktestOutcome {

        /**
         * @throws NullPointerException if {@code state} is {@code null}
         */
        public Aborted {
            Objects.requireNonNull(state, "state");
        }

        /** {@return {@code true} — the run finished, by being stopped} */
        @Override
        public boolean finished() {
            return true;
        }
    }

    /**
     * The platform knows this job but has nothing final to report yet. Ask
     * again later; nothing is wrong.
     *
     * <p>This is also what a job the platform cannot yet describe at all reads
     * as — it answers those without a body, so {@link #state()} is
     * {@code null} rather than a status. A run that is genuinely under way
     * carries one, with {@link JobState#getSize()} and
     * {@link JobState#getCompleted()} saying how far along it is.
     *
     * <p>Waiting for a run to finish is {@link Backtest#await()}'s job, on the
     * process that started it. This variant is a snapshot, not a promise: a
     * standalone read does not poll.
     *
     * @param state   the platform's job record, or {@code null} when the
     *                response carried no body
     * @param results anything the platform sent alongside, which for an
     *                unfinished run is not an account of it
     */
    record InProgress(JobState state, ResultMap results) implements BacktestOutcome {

        /** {@return {@code false} — the run has not stopped moving} */
        @Override
        public boolean finished() {
            return false;
        }
    }
}
