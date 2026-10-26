package com.qtsurfer.api.sdk;

import com.qtsurfer.api.client.model.DeclaredProperty;

import java.util.List;

/**
 * Compilation metadata discovered without constructing a strategy. The property list is
 * best-effort: an absent name may still be valid when it is registered by attached runtime config.
 */
public record CompiledStrategy(String id, List<DeclaredProperty> declaredProperties, List<String> requires) {
    public CompiledStrategy(String id, List<DeclaredProperty> declaredProperties) {
        this(id, declaredProperties, List.of());
    }

    public CompiledStrategy {
        declaredProperties = declaredProperties == null ? List.of() : List.copyOf(declaredProperties);
        requires = requires == null ? List.of() : List.copyOf(requires);
    }
}
