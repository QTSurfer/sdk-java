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
new token. An invalid, revoked, or expired API key raises `QTSAuthError`; where the deployment
requires scopes, a valid key with none returns `403` `apikey_without_scopes` and no token. Add at
least one resource/action scope to the key in the web app; retrying the same key will not help.
`QTSAuthError.getAuthTokenError()` retains the typed server error, including its code and message;
`getStatusCode()` reports the HTTP status. A rate limit or other API failure raises `QTSError`.

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

## Read granted scopes

`AuthTokenResponse.getScopes()` and `qts.getAccount().getScopes()` return the same map of
resource names to action lists, for example `{"backtests":["read","write","execute"],
"exchanges":["read"]}`. Names are lower case; a key with no scopes has `{}`. Scopes are reported
but the API does not yet reject an operation for a missing scope. Do not infer enforcement from
the presence of this map. See the [API auth guide](https://www.qtsurfer.com/docs/developers/api/auth) for
resource and action definitions.

Custom token stores must preserve the nested map when serializing `AuthTokenResponse`. If an old
persisted token has the former `scopes: []` shape, discard it and obtain a new token, or migrate
only an empty legacy list to `{}` before reading it with the current generated model.
