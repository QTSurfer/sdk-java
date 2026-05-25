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

    @Override
    public AuthTokenResponse load() {
        return ref.get();
    }

    @Override
    public void save(AuthTokenResponse token) {
        ref.set(token);
    }

    @Override
    public void clear() {
        ref.set(null);
    }
}
