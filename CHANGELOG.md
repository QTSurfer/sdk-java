# Changelog

All notable changes to `net.qtsurfer:sdk` are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
