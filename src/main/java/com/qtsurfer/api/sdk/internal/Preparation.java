package com.qtsurfer.api.sdk.internal;

import com.qtsurfer.api.client.api.BacktestingApi;
import com.qtsurfer.api.client.model.AcceptedJob;
import com.qtsurfer.api.client.model.DataSourceType;
import com.qtsurfer.api.client.model.PrepareJobState;
import com.qtsurfer.api.client.model.PrepareRequest;
import com.qtsurfer.api.sdk.errors.QTSCanceledError;
import com.qtsurfer.api.sdk.errors.QTSPreparationError;
import com.qtsurfer.api.sdk.internal.StatusNormalizer.Normalized;

import java.time.Duration;
import java.util.function.Consumer;

/**
 * The prepare stage, shared by every workflow that needs a dataset before it
 * can execute anything.
 *
 * <p>One implementation on purpose. Preparing is idempotent — the same
 * instrument and window always resolve to the same job — so a workflow that
 * prepares on every call duplicates no work, and that argument only holds
 * while there is a single place where it is true.
 */
public final class Preparation {

    private Preparation() {}

    /**
     * Submit a prepare and poll it to a terminal state.
     *
     * @param api             the generated backtesting api
     * @param exchangeId      exchange identifier
     * @param type            data source type
     * @param body            instrument and window to prepare
     * @param pollInterval    initial interval between status polls
     * @param maxPollInterval upper bound of the exponential backoff
     * @param timeout         deadline for the stage; {@code null} disables
     * @param onPercent       called after each poll that reports size information; may be {@code null}
     * @param onPrepared      called once with the terminal state; may be {@code null}
     * @return the prepare jobId, which identifies the prepared dataset
     * @throws QTSPreparationError when the submission, a status read, or the preparation itself fails
     * @throws QTSCanceledError    when the preparation is aborted server-side
     */
    public static String prepare(
            BacktestingApi api,
            String exchangeId,
            DataSourceType type,
            PrepareRequest body,
            Duration pollInterval,
            Duration maxPollInterval,
            Duration timeout,
            Consumer<Double> onPercent,
            Consumer<PrepareJobState> onPrepared) {

        AcceptedJob accepted = ApiCalls.call(
                () -> api.prepareBacktest(exchangeId, type, body),
                "Prepare submission failed",
                QTSPreparationError::new);
        if (accepted == null || accepted.getJobId() == null) {
            throw new QTSPreparationError("Missing jobId in prepare response");
        }
        String prepareJobId = accepted.getJobId();

        PrepareJobState state = Polling.poll(
                "PREPARING",
                pollInterval, maxPollInterval, timeout,
                () -> ApiCalls.call(
                        () -> api.getPrepareStatus(exchangeId, type, prepareJobId),
                        "Preparation status request failed",
                        QTSPreparationError::new),
                r -> StatusNormalizer.normalize(r.getStatus()) == Normalized.IN_PROGRESS,
                r -> {
                    if (onPercent == null) return;
                    Double percent = Polling.percent(r.getSize(), r.getCompleted());
                    if (percent != null) onPercent.accept(percent);
                });

        Normalized norm = StatusNormalizer.normalize(state.getStatus());
        if (norm == Normalized.FAILED) {
            throw new QTSPreparationError(
                    detailOrDefault(state.getStatusDetail(), "Data preparation failed"));
        }
        if (norm == Normalized.ABORTED) {
            throw new QTSCanceledError("Data preparation aborted");
        }
        if (onPrepared != null) {
            onPrepared.accept(state);
        }
        return prepareJobId;
    }

    private static String detailOrDefault(String detail, String fallback) {
        return (detail == null || detail.isBlank()) ? fallback : detail;
    }
}
