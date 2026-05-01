# Changelog

All notable changes to `net.qtsurfer:sdk` are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
