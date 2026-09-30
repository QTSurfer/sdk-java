package com.qtsurfer.api.sdk;

import com.qtsurfer.api.client.model.SendLiveCommandRequest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Builds a live command while keeping its arbitrary properties in a fluent API. */
public final class LiveCommandRequestBuilder {
    private String command;
    private final Map<String, Object> properties = new LinkedHashMap<>();

    private LiveCommandRequestBuilder() {}

    /** Start building a live command. */
    public static LiveCommandRequestBuilder builder() {
        return new LiveCommandRequestBuilder();
    }

    /** Set the non-blank command text handled by the running strategy. */
    public LiveCommandRequestBuilder command(String command) {
        this.command = Objects.requireNonNull(command, "command");
        return this;
    }

    /** Add or replace one arbitrary JSON property delivered alongside the command. */
    public LiveCommandRequestBuilder property(String name, Object value) {
        properties.put(Objects.requireNonNull(name, "name"), value);
        return this;
    }

    /** Add or replace several arbitrary JSON properties. */
    public LiveCommandRequestBuilder properties(Map<String, ?> values) {
        Objects.requireNonNull(values, "values").forEach(this::property);
        return this;
    }

    /** Build the generated request accepted by the API client. */
    public SendLiveCommandRequest build() {
        return new SendLiveCommandRequest().command(Objects.requireNonNull(command, "command"))
                .properties(new LinkedHashMap<>(properties));
    }
}
