package com.qtsurfer.api.sdk.internal;

import com.qtsurfer.api.client.invoker.ApiResponse;
import com.qtsurfer.api.client.model.StrategyState;
import com.qtsurfer.api.sdk.ValidationOutcome;
import com.qtsurfer.api.sdk.errors.QTSError;

/**
 * Reads the queued-or-not distinction off the HTTP status of a validation
 * request.
 *
 * <p>The api-client's convenience overload returns only the deserialized
 * body, which is the same type for both answers, so the SDK calls the
 * {@code WithHttpInfo} variant and interprets the status here. {@code 202} is
 * the only status that means a check was started; every other success carries
 * the state the platform already holds.
 */
public final class ValidationOutcomes {

    private ValidationOutcomes() {}

    /**
     * @param strategyId the id the caller asked about — echoed into a
     *                   {@link ValidationOutcome.Queued}, which does not
     *                   depend on the response body carrying one
     * @param response   the raw api-client response
     * @return the outcome the status describes
     * @throws QTSError if a non-queued response carried no body to read
     */
    public static ValidationOutcome of(String strategyId, ApiResponse<StrategyState> response) {
        if (response.getStatusCode() == 202) {
            return new ValidationOutcome.Queued(strategyId);
        }
        StrategyState state = response.getData();
        if (state == null) {
            throw new QTSError("validateStrategy call failed: HTTP "
                    + response.getStatusCode() + " carried no strategy state");
        }
        return new ValidationOutcome.NotQueued(state);
    }
}
