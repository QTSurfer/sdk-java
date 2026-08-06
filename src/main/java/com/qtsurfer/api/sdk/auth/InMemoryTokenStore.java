package com.qtsurfer.api.sdk.auth;

import com.qtsurfer.api.client.model.AuthTokenResponse;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Default {@link TokenStore} — holds the most recent token in a single
 * in-memory slot. Lost on JVM exit. Sufficient for short-lived scripts and
 * for tests.
 */
public final class InMemoryTokenStore implements TokenStore {

    private final AtomicReference<AuthTokenResponse> ref = new AtomicReference<>();

    /**
     * Returns whatever was last written to the single in-memory slot —
     * {@code null} before the first {@link #save} or after {@link #clear()}.
     */
    @Override
    public AuthTokenResponse load() {
        return ref.get();
    }

    /** Overwrites the single slot; passing {@code null} has the same effect as {@link #clear()}. */
    @Override
    public void save(AuthTokenResponse token) {
        ref.set(token);
    }

    /** Drops the in-memory token; nothing here survives past this call or JVM exit. */
    @Override
    public void clear() {
        ref.set(null);
    }
}
