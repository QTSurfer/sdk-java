package com.qtsurfer.api.sdk.internal;

import com.qtsurfer.api.client.invoker.ApiException;
import com.qtsurfer.api.sdk.errors.QTSError;

import java.util.function.BiFunction;

/**
 * Turns a generated api-client call into an SDK call: the checked
 * {@link ApiException} becomes the {@link QTSError} subclass the calling stage
 * is defined in terms of, with the HTTP status and response body folded into
 * the message.
 */
public final class ApiCalls {

    private ApiCalls() {}

    /** An api-client invocation, which may fail with the generated checked exception. */
    @FunctionalInterface
    public interface ApiCall<T> {
        /**
         * Perform the call.
         *
         * @return the api-client response
         * @throws ApiException on HTTP 4xx/5xx or transport failure
         */
        T invoke() throws ApiException;
    }

    /**
     * Invoke {@code call}, mapping an {@link ApiException} to the error type
     * built by {@code errorCtor}.
     *
     * @param call      the api-client invocation
     * @param message   prefix describing what was being attempted
     * @param errorCtor builds the SDK error from the message and the cause
     * @param <T>       response type
     * @param <E>       SDK error type raised on failure
     * @return whatever the call returned
     */
    public static <T, E extends QTSError> T call(
            ApiCall<T> call,
            String message,
            BiFunction<String, Throwable, E> errorCtor) {
        try {
            return call.invoke();
        } catch (ApiException e) {
            throw errorCtor.apply(message + ": " + describe(e), e);
        }
    }

    /** Render an {@link ApiException} as {@code HTTP <code>} plus its body when it carries one. */
    public static String describe(ApiException e) {
        if (e.getResponseBody() != null && !e.getResponseBody().isBlank()) {
            return "HTTP " + e.getCode() + " — " + e.getResponseBody();
        }
        return "HTTP " + e.getCode();
    }
}
