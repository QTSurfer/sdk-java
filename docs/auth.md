# Authentication

The SDK exchanges an API key for a short-lived JWT and uses that JWT for API requests. API keys are
only sent to the authentication endpoint.

## Default authenticated session

Set `QTSURFER_APIKEY` and let the SDK manage the session:

```java
import com.qtsurfer.api.sdk.QTSurfer;
import com.qtsurfer.api.sdk.auth.AuthenticatedClient;

AuthenticatedClient qts = QTSurfer.authenticate();
```

Pass the key directly when environment configuration is unsuitable:

```java
AuthenticatedClient qts = QTSurfer.authenticate("ak_...");
```

`AuthenticatedClient` refreshes a token before it expires and retries one `401` after obtaining a
new token. An invalid, revoked, or expired API key raises `QTSAuthError`; a rate limit or other API
failure raises `QTSError`.

## Persisting tokens

Tokens are in memory by default. Implement `TokenStore` to keep them in a file, keychain, or secret
manager. The store owns the storage security policy.

```java
import com.qtsurfer.api.client.model.AuthTokenResponse;
import com.qtsurfer.api.sdk.QTSurfer;
import com.qtsurfer.api.sdk.auth.AuthOptions;
import com.qtsurfer.api.sdk.auth.TokenStore;

TokenStore store = new TokenStore() {
    @Override public AuthTokenResponse load() { return null; }
    @Override public void save(AuthTokenResponse token) { }
    @Override public void clear() { }
};

var qts = QTSurfer.authenticate(null, AuthOptions.builder().store(store).build());
```

`AuthOptions` also accepts `baseUrl`, `httpClient`, and `executor` for custom targets, transports,
and execution resources.

## Hand-managed JWT

Use `QTSurfer.builder()` only when the caller owns JWT refresh:

```java
import com.qtsurfer.api.sdk.QTSurfer;

QTSurfer qts = QTSurfer.builder()
        .baseUrl("https://api.qtsurfer.net/v1")
        .token(System.getenv("JWT_API_TOKEN"))
        .build();
```

This entry point does not exchange an API key or refresh the supplied token.
