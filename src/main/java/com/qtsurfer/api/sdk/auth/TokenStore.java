package com.qtsurfer.api.sdk.auth;

import com.qtsurfer.api.client.model.AuthTokenResponse;

/**
 * Pluggable token persistence strategy for {@link AuthenticatedClient}.
 *
 * <p>The SDK ships an {@link InMemoryTokenStore} as the default. Adopters
 * implement this SAM to back tokens by an on-disk file, a secret manager,
 * etc. The SDK calls {@link #load()} once at session start to seed any
 * previously cached token, {@link #save(AuthTokenResponse)} after every
 * successful {@code auth()} / refresh, and {@link #clear()} when the
 * session is explicitly invalidated.
 *
 * <p>Implementations are expected to be thread-safe.
 */
public interface TokenStore {

    /** Return the persisted token, or {@code null} if none. */
    AuthTokenResponse load();

    /** Persist the token returned by {@code POST /v1/auth/token}. */
    void save(AuthTokenResponse token);

    /** Drop any persisted token. */
    void clear();
}
