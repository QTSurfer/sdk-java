# Changelog

All notable changes to `com.qtsurfer:sdk` are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.9.0] — 2026-08-06

### Changed 🔄

- **Compiling a strategy is now a single request.** The API compiles synchronously and answers with
  the `strategyId`, so the SDK no longer submits with `X-Compile-Async` and then polls a compile
  job to completion. `QTSurfer.compile(...)` and `BacktestWorkflow.compile(...)` keep their
  signatures and their behaviour from a caller's point of view — the same handle comes back — but
  they now resolve in one round trip instead of one plus a poll loop, and a compile error surfaces
  immediately rather than on a later poll.
- `StrategyCompileClient` (internal) collapses to a single `compile(String source)` returning the
  `strategyId`; `submit` and `status` are gone, as is the `CompileStatus` record they returned.
  Callers outside this library are not expected to touch `com.qtsurfer.api.sdk.internal`, but the
  types were reachable, so this is called out rather than treated as invisible.
- Bumped `com.qtsurfer:api-client` to `0.7.0` (OpenAPI spec `0.102.0`).

### Added ✨

- A `429` from the compile endpoint is reported as its own condition — the platform is holding too
  many compilations at once and the source was never judged — instead of being worded as a
  compilation failure like every other `4xx` on that endpoint.

## [0.8.1] — 2026-07-27

### Fixed 🐛

- **A `202` on the execute-result poll no longer ends the poll with an empty result.** The API
  answers `202` with an empty body when a job is known but its result is not readable yet, so a
  successful response can legitimately carry no `state`. `BacktestWorkflow` dereferenced
  `getState()` inside the retry predicate, and the resulting `NullPointerException` was swallowed
  by the retry policy: the predicate simply did not match, the poll stopped, and the caller
  received a `null` `ResultMap` for a backtest that had actually completed. The status is now read
  through a null-safe accessor, so an absent state normalizes to "in progress" and the loop asks
  again under its existing timeout.

## [0.8.0] — 2026-07-18

### Changed (BREAKING)

- **`QTSurfer.auth(...)` / `AuthenticatedClient.auth(...)` renamed to `authenticate(...)`** (all three overloads: no-arg, `(apikey)`, `(apikey, AuthOptions)`). The generated client's `AuthApi.auth()` operation was renamed to `AuthApi.authenticate()` (API spec 0.99.1); the SDK's own entry point is renamed to match, so the "authenticate" name is consistent end-to-end from the wire operationId through the SDK's public facade.

  ```diff
  - AuthenticatedClient qts = QTSurfer.auth(apikey);
  + AuthenticatedClient qts = QTSurfer.authenticate(apikey);
  ```

### Changed 🔄

- Bumped `com.qtsurfer:api-client-java` to `0.6.0` (API spec 0.99.1, 16 operationId renames — no request/response shape, field, or endpoint changes). Internal call sites in `BacktestWorkflow` now use the renamed generated methods (`prepareBacktest`, `getPrepareStatus`, `executeBacktest`, `cancelBacktest`, `getBacktestResult`) and `AuthenticatedClient`/`QTSurfer` use `listExchanges`/`listInstruments`; no change to any of those classes' other public signatures.
- Two generated request types were renamed as a byproduct of the operationId renames (`PrepareBacktestingRequest` → `PrepareRequest`, `ExecuteBacktestingRequest` → `ExecuteBacktestRequest`). Both are internal to `BacktestWorkflow` and were never exposed on the SDK's public surface, so this is not a breaking change on its own.

## [0.7.0] — 2026-07-11

### Added ✨

- **Prepare coverage is surfaced on progress.** `BacktestProgress` gains a `coverageRatio` field (0–1) reported by the backend once preparation completes — the fraction of the requested window that actually holds data. It is non-null only on the final `PREPARING` event, letting callers react to a partially-covered range. Existing two-argument `BacktestProgress(stage, percent)` construction keeps working via a convenience constructor.

### Changed 🔄

- Bumped `com.qtsurfer:api-client-java` to `0.5.0` (API spec 0.98.0). The single-instrument preparation endpoint now returns `PrepareJobState` (adds `coverageRatio`, `totalHours`, `hoursWithData`, and a per-hour `hoursWithoutData` breakdown); the backtest workflow reads the new type internally with no change to its public method signatures.

### Removed 🗑️

- Dropped the defensive `Partial` prepare-status handling: spec 0.98.0 removed `Partial` from the job status enum (a single-instrument prepare is always terminal on `Completed`, and callers decide from `coverageRatio`). `StatusNormalizer` no longer special-cases it.

## [0.6.1] — 2026-07-10

### Fixed 🐛

- **Prepare is treated as terminal on `Partial`, not just `Completed`.** An instrument's coverage can span hours that hold no ticks — e.g. the tickers window `2026-07-10T13:00Z → 15:30Z`, where only the `14:00–15:00` hour traded — in which case the prepare reports partial coverage. The backtest workflow now proceeds to execute on the available data instead of polling that state until it times out.

## [0.6.0] — 2026-07-10

### Changed 🔄

- Bumped `com.qtsurfer:api-client-java` to `0.4.0`, which wraps `ExchangeApi.getInstruments(exchangeId)` in an `InstrumentListResponse` HAL envelope (OpenAPI spec 0.97.0). `QTSurfer#instruments(String)` and `AuthenticatedClient#instruments(String)` now unwrap that envelope internally (`.getData()`), so both keep returning `List<InstrumentDetail>` — no signature or behavior change for SDK callers.
- `InstrumentDetail` no longer carries flat `dataFrom`/`dataTo` fields upstream; use `InstrumentDetail#getCoverage()` (`InstrumentCoverage` → `tickers`/`klines` `CoverageWindow`) for data-availability windows instead.

### Fixed 🐛

- Bumped Mockito `5.14.2` → `5.23.0` (aligning with `mcp-java`) so the test suite's Byte Buddy mock instrumentation runs on JDK 25.

## [0.5.0] — 2026-05-25

### Added

- `QTSurfer.auth(apikey)` (plus `auth()` env-var overload and `auth(apikey, AuthOptions)`) — one-call helper that exchanges a long-lived API key for a short-lived JWT and returns an `AuthenticatedClient`. The returned session mirrors the existing `QTSurfer` surface (`compile`, `backtest`, `exchanges`, `instruments`, `tickers`, `klines`) but transparently refreshes the JWT once on a 401 before retrying.
- `com.qtsurfer.api.sdk.auth.TokenStore` interface (`load` / `save` / `clear`) and a default `InMemoryTokenStore`. Adopters can plug in a file, secret manager, or desktop keychain. `AuthOptions` is the configuration record (base URL, token store, HTTP client, executor) — defaults to `https://api.qtsurfer.com/v1` and an in-memory store.
- `QTSURFER_APIKEY` environment variable: read by `QTSurfer.auth(null, ...)` / `QTSurfer.auth()` when no API key argument is passed.
- `QTSAuthError` (subclass of `QTSError`) raised when no API key is available or the JWT exchange fails.

### Changed

- Bumped `com.qtsurfer:api-client-java` to `0.3.1` (adds `AuthApi`, `AuthTokenResponse`, `AuthTokenError`).
- `DownloadFormat#wire()` is now `public` so the auth-session can pass the underlying `ExchangeBinaryDownloads.Format` through.

## [0.4.1] — 2026-05-17

### Fixed

- Corrected JitPack dependency coordinate for `api-client`: `com.qtsurfer:api-client-java:0.2.0` (JitPack uses the repo name as artifactId, not the pom artifactId).

## [0.4.0] — 2026-05-17

### Changed

- Maven coordinates migrated to `com.qtsurfer:sdk-java` via JitPack custom domain (`git.qtsurfer.com`). Consumers should replace `com.github.QTSurfer:sdk-java:v0.3.x` with `com.qtsurfer:sdk-java:0.4.1`.
- Java packages renamed from `net.qtsurfer.api.sdk` to `com.qtsurfer.api.sdk` throughout.
- Dependency on `com.qtsurfer:api-client-java:0.2.0` (previously `com.github.QTSurfer:api-client-java:v0.1.2`).
- Tags no longer use the `v` prefix; CI release workflow updated accordingly.

## [0.3.0] — 2026-05-17

### Added

- **Exchange & instrument discovery:**
  - `QTSurfer#exchanges()` → `List<Exchange>` — list all exchanges available on the platform.
  - `QTSurfer#instruments(String exchangeId)` → `List<InstrumentDetail>` — list instruments for a given exchange, including `dataFrom`/`dataTo` availability windows, `lastPrice`, and `volume24h`.
  - Both methods wrap `com.qtsurfer.api.client.api.ExchangeApi` (already generated in `api-client v0.1.2`) and surface failures as `QTSError`.

## [0.2.0] — 2026-05-01

### Added

- **Domain objects (`Strategy`, `Backtest`):**
  - `QTSurfer#compile(...)` returns a reusable `Strategy` handle that can launch multiple backtests.
  - `Strategy#backtest(...)` returns a `Backtest` handle exposing `id()`, `state()`, `progress()` (a `Flow.Publisher<BacktestProgress>`), `await()`, and `cancel()`.
  - `QTSurfer#backtest(request, options)` shortcut now composes `compile → backtest → await` over the new objects.
- **Hourly tickers/klines downloads:**
  - `QTSurfer#tickers(exchangeId, base, quote, hour[, format])` and `QTSurfer#klines(...)` — stream one hour of raw tickers or klines as `InputStream`.
  - `DownloadFormat` enum (`LASTRA` default, `PARQUET` for on-the-fly conversion).
  - `QTSDownloadError` (subclass of `QTSError`) — surfaced when the download fails (HTTP 4xx/5xx, transport error).

### Changed

- `api-client` dependency bumped to `v0.1.2` (adds `ExchangeBinaryDownloads`).
- Internal `Backtest` workflow class renamed to `BacktestWorkflow` to free the public `Backtest` name for the new domain handle.

### Removed

- Hardcoded staging URL from the integration test default; `QTSURFER_API_URL` is now required alongside `JWT_API_TOKEN` (the test skips when either is absent).
- Javadoc and README examples use the public domain (`api.qtsurfer.com`) instead of internal/staging URLs.

## [0.1.0] — 2026-04-15

### Added

- Initial release of `net.qtsurfer:sdk`, an opinionated Java SDK built on top of [`net.qtsurfer:api-client`](https://github.com/QTSurfer/api-client-java).
- `QTSurfer` facade with a fluent builder (`baseUrl`, `token`, optional `httpClient` / `executor`).
- `QTSurfer.backtest(BacktestRequest, BacktestOptions)` — orchestrates compile → prepare → execute and returns a `CompletableFuture<ResultMap>`.
- Polling, exponential backoff, and per-stage timeouts delegated to [Failsafe](https://failsafe.dev) policies.
- Best-effort server-side `cancelExecution` when the returned future is cancelled after the execute stage has started.
- Error hierarchy: `QTSError`, `QTSStrategyCompileError`, `QTSPreparationError`, `QTSExecutionError`, `QTSTimeoutError`, `QTSCanceledError`.
- Status normalizer handling casing drift between the OpenAPI spec and the live API (`queued`, `completed`, `failed`, `aborted`, …).
- SLF4J API hook for logging (consumers bring their own binding).
- Distribution via [JitPack](https://jitpack.io/#QTSurfer/sdk-java).
