package com.qtsurfer.api.sdk.internal;

import com.fasterxml.jackson.databind.MapperFeature;
import com.qtsurfer.api.client.invoker.ApiClient;

/**
 * Builds the generated {@link ApiClient} the way every workflow of the SDK needs it.
 *
 * <p>The generated client reads a {@code oneOf} by trying each of its types and requiring exactly
 * one to accept the value. With Jackson's default scalar coercion a JSON number is also accepted
 * as a string and as a boolean, so {@code ScalarStrategyParamValue} (number, string or boolean)
 * matched two or three types for every number and boolean and failed with "N classes match
 * result, expected 1": a run started with a numeric or boolean {@code params} value came back
 * unreadable, though the platform had run it. Coercion is therefore off, which is also what a
 * {@code oneOf} of scalars needs to tell its members apart.
 *
 * <p>The mapper has to be <em>installed</em> on the client. {@link ApiClient#getObjectMapper()}
 * hands out a copy on every call, so configuring what it returns changes nothing; and the API
 * classes take their mapper from the client when they are built, so this is done before any of
 * them is.
 */
public final class ApiClients {

    private ApiClients() {
    }

    /** A client whose mapper does not coerce scalars between JSON types. */
    @SuppressWarnings("deprecation")
    public static ApiClient create() {
        ApiClient client = new ApiClient();
        client.setObjectMapper(
                ApiClient.createDefaultObjectMapper().configure(MapperFeature.ALLOW_COERCION_OF_SCALARS, false));
        return client;
    }
}
