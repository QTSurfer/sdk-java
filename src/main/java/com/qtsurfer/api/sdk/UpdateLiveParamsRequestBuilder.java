package com.qtsurfer.api.sdk;

import com.qtsurfer.api.client.model.UpdateLiveParamsRequest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Builds a live-parameter update without exposing the generated untyped request field. */
public final class UpdateLiveParamsRequestBuilder {
    private final Map<String, Object> params = new LinkedHashMap<>();

    private UpdateLiveParamsRequestBuilder() {}

    /** Start a live-parameter update. */
    public static UpdateLiveParamsRequestBuilder builder() {
        return new UpdateLiveParamsRequestBuilder();
    }

    /** Add or replace one strategy parameter. */
    public UpdateLiveParamsRequestBuilder param(String name, Object value) {
        params.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
        return this;
    }

    /** Add or replace several strategy parameters. */
    public UpdateLiveParamsRequestBuilder params(Map<String, ?> values) {
        Objects.requireNonNull(values, "values").forEach(this::param);
        return this;
    }

    /** Build the generated request accepted by the API client. */
    public UpdateLiveParamsRequest build() {
        return new UpdateLiveParamsRequest().params(Map.copyOf(params));
    }
}
