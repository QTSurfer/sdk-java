# Changelog

All notable changes to `com.qtsurfer:sdk` are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.13.1] — 2026-08-13

### Fixed 🐛

- **A session left idle past the JWT's `expires_in` window (1hr) started failing every call
  with a raw `401`.** `ensureToken()` used to return whatever was cached forever; refresh only
  ever happened reactively, and only for calls routed through the generated client. Every call
  now checks the cached token's remaining TTL first and proactively re-mints a short skew ahead
  of expiry, so an idle session mints on the next call instead of sending one already stale.
- **`compile(...)`'s `401` was never retried**, unlike every other call — it talks to its
  endpoint directly rather than through the generated client, and threw with no cause the
  refresh-on-401 policy could recognize. Its `401` now carries the same `ApiException` cause the
  rest of the SDK's failures do, so it (and `backtest(...)`/`sweep(...)`, whose pipeline compiles
  through the same client internally) get the same one-refresh-one-retry as everything else.

## [0.13.0] — 2026-08-12

### Added ✨

- **Standalone read of a backtest result.** Both entry points (`QTSurfer` and
  `AuthenticatedClient`) gain `backtestResult(exchangeId, jobId)` → `BacktestOutcome` — one
  synchronous read of a run the calling process did not necessarily start. Until now a run's
  numbers were reachable only through the `Backtest` handle, and that handle exists only where the
  run was submitted: a job id arriving from another client, another session, or the same process
  before a restart had no route at all.

  **Why a standalone read exists when the workflow stages deliberately do not.** `prepareBacktest`,
  `getPrepareStatus` and `executeBacktest` have no standalone method on purpose, and that has not
  changed. They are stages of a run *you are performing*: they own the dataset lifecycle, preparing
  answers with the job id of the prepared window, and exposing them would hand the caller that id
  to hold and pass on to every later call. Reading a result for a job id you were simply *given* is
  not a stage of anything — it is a query against a resource the platform already holds, the same
  category as `Sweep.results(...)` and `Sweep.sensitivity(...)`, which are standalone for exactly
  this reason. It compiles nothing, prepares nothing, and submits nothing.

  `exchangeId` is required and is not defaulted. A run's result is addressed under the exchange it
  was submitted against, so a job id on its own does not identify the resource — there is nothing
  sensible to default the exchange to, and an id carried to the wrong exchange does not name the
  same run.

  **A run that ended badly is an answer, not an exception.** `Backtest.await()` completes
  exceptionally with `QTSExecutionError` or `QTSCanceledError` on a failed or aborted run, and that
  is right for a caller waiting on a result it asked for: it is not getting one. A caller asking
  *what happened to this job* is getting one, so the standalone read reports the ending in its
  return value instead. New sealed `BacktestOutcome` with the four answers the platform can give —
  `Completed`, `Failed`, `Aborted`, `InProgress` — plus `state()`, `results()` and a `finished()`
  shortcut. Only a job id the platform does not recognise raises.

  Those four are not invented: they are the cases `StatusNormalizer` already reduces a job status
  to, so the read classifies a run by exactly the rule the poll loop stops on. `InProgress` is also
  where a job the platform cannot describe yet lands — it answers those with an empty body, so that
  is the one variant whose `state()` can be absent.

  Same reasoning as `ValidationOutcome`, added in 0.11.0: one flat return type was collapsing two
  independent questions, and a sealed type keeps them apart. Here the questions are *how did this
  run end* and *what did it produce* — and since the platform always sends the job state, returning
  the results alone would have thrown away the answer to the first one.

### Changed 🔄

- **Standalone reads now raise `QTSError` rather than `QTSExecutionError`.** This affects
  `Sweep.results(order)` / `results(order, ranking)`, shipped in 0.12.0, and the new
  `backtestResult(...)`. `Sweep.sensitivity(...)` already behaved this way and is unchanged.

  `QTSExecutionError` means the execute stage of a run this SDK is performing failed. A read is not
  performing a run: it never was for `Sweep.results(...)`, and for `backtestResult(...)` a run that
  failed or was aborted is now a value rather than an exception — so everything either of them can
  still raise is transport or HTTP. A caller catching the subtype to mean "my run blew up" would
  have been catching the wrong thing. `QTSExecutionError`'s javadoc has been corrected to say it
  covers sweeps as well as single backtests, and that reads do not raise it.

  **This is technically breaking**, and worth stating rather than filing as a tidy-up: anyone
  catching `QTSExecutionError` *specifically* around `Sweep.results(...)` will stop matching. The
  declared contract does not change — both methods have always documented `@throws QTSError`, the
  parent — so `catch (QTSError …)` is unaffected, as is any caller that lets it propagate. The
  practical exposure is nil because 0.12.0 is hours old, and that is exactly why it is being
  corrected now: the same change against an established release would cost real users.

  The polling paths are untouched and still raise `QTSExecutionError`: a transport fault while
  driving a run *is* that run's execute stage failing. Both workflows keep one shared call site per
  endpoint and pass the error type in, so the read and the poll cannot drift apart on the route
  while still differing on what a fault there means.

## [0.12.0] — 2026-08-12

### Added ✨

- **Parameter sweeps.** Both entry points (`QTSurfer` and `AuthenticatedClient`) gain
  `sweep(request)` / `sweep(request, options)` → `CompletableFuture<Sweep>`, running the same
  compile → prepare → submit pipeline as `backtest(...)` and then polling the leaderboard in the
  background. One call rather than composable stages: the sweep endpoint is addressed by the id of
  an already-prepared dataset, and preparing is idempotent, so sweeping the same window twice
  prepares it once.
  - `SweepRequest` (+ builder) — the grid, the instrument, and the window. Axes are `ParamAxis`,
    a sealed `range(from, to, step)` / `of(values…)` pair, because the two shapes are mutually
    exclusive on the wire. Optional `sampler`, `samples`, `seed`, `objective` and `walkForward`.
  - `SweepOptions` (+ builder) — poll interval, backoff ceiling, per-stage timeout, progress
    callback, `ranking`, and `order`. Poll intervals default longer than a backtest's, since a
    sweep's leaderboard changes on the timescale of shards finishing.
  - `Sweep` — the handle: `id()`, `requestId()`, `strategyId()`, `accepted()`, `state()`,
    `progress()` (a `Flow.Publisher<SweepProgressEvent>`), `await()`, `results(order)` /
    `results(order, ranking)`, `cancel()`, and `sensitivity()` / `sensitivity(objective)`.
  - `SweepObjective`, `SweepRanking`, `SweepOrder`, `SweepSampler`, `WalkForwardSpec`,
    `SweepProgressEvent`, and `com.qtsurfer.api.sdk.workflows.SweepWorkflow`.

  **The leaderboard's default order is not the raw objective order.** It is plateau order: a
  point's plateau score is the objective of the worst run in its immediate neighbourhood, so a
  point ranks well only if the region around it does too — the highest raw score is frequently a
  spike that does not survive the parameters moving slightly. `SweepRanking.RAW` asks for the
  unadjusted order, and `result.getRanking()` reports which was *actually* applied, which is not
  always the one requested: a sweep with no stored parameter grid falls back to raw. Read
  `plateauScore` and `neighbourCount` together — `neighbourCount: 0` means the score is
  unevidenced, not confirmed.

  **That leaderboard is also capped**, and `truncated` says when the cap bit.
  `SweepOrder.NATURAL` returns every available row instead, in deterministic `runIx` order — the
  view to read when materialising durable trial rows, and the way to reach rows the ranked view
  dropped. `ranking` is ignored on that view, which is always ordered by `runIx`.

  The view is a query parameter on the read rather than a property of the run, so `Sweep.results(order)`
  / `results(order, ranking)` re-reads the same sweep in a different view — one request, no compile,
  no prepare, no second sweep — and works on a sweep still in flight, returning the rows finished
  so far. `SweepOptions.order(...)` sets the view the background poll uses, and so the one
  `await()` resolves with.

  **A walk-forward sweep answers in a different shape**, and `walkForward` on the response is the
  discriminator — present from acceptance onward (also on `sweep.accepted()`), so it is safe to
  branch on while polling. Its leaderboard is one row per completed fold, that fold's winner as it
  scored out-of-sample, with `runIx` carrying the fold index rather than a grid position; no
  plateau, deflated-Sharpe or PBO figure is reported for one. `paramDrift` absent is not zero — the
  field is omitted when it could not be computed, and zero is itself a meaningful reading.

  **`Sweep.await()` resolves on cancellation** instead of raising, unlike `Backtest.await()`. A
  cancelled sweep keeps every row it already scored, so the SDK polls until the platform reports
  `CANCELLED` and hands back the partial leaderboard; read `result.getStatus()`. `PARTIAL` is
  likewise terminal, and since there is no sweep-wide failed status, a sweep whose every shard died
  is `PARTIAL` with an empty leaderboard.

  **`Sweep.sensitivity(...)`** returns the whole `SweepSensitivity` — marginals, heatmaps, and
  `heatmapsTruncated`. The flag is the point: the two-parameter surfaces are quadratic in the axis
  count and may be capped, and a silently short list would read as "these are all the
  interactions".

### Changed 🔄

- `StatusNormalizer` treats `partial` as terminal. Only the sweep statuses use that value — a sweep
  that finishes with a dead shard reports it, and its rows are readable — so a poll that read it as
  "still running" would never stop. The prepare and execute paths are unaffected.
- The prepare stage and the polling loop moved into `com.qtsurfer.api.sdk.internal`
  (`Preparation`, `Polling`, `ApiCalls`), shared by the backtest and sweep workflows rather than
  duplicated. Behaviour is unchanged; callers outside this library are not expected to touch
  `…sdk.internal`, but the types are reachable, so this is named rather than treated as invisible.
- Bumped `com.qtsurfer:api-client-java` to `0.9.0` (API spec `0.107.0`). `ExecuteSweepResult` gains
  `getFailReason()`, so a sweep that finished having scored nothing can now say why instead of
  handing back an empty leaderboard and no explanation — the platform always reported this and the
  contract never declared it, so every client dropped it. `Sweep.await()` documents how to read it,
  including that only the first failing shard's cause is recorded.

### Fixed 🐛

- **Cancelling the future from the `backtest(...)` shortcut does not stop the run** — it ends your
  wait while the backtest keeps running server-side, still billing. The shortcut composes its
  stages with `thenCompose`, and cancelling a composed `CompletableFuture` does not propagate back
  through the chain. Behaviour is unchanged and intentional; what changed is that the documentation
  said the opposite, so anyone who relied on it was not stopping the work they thought they were.
  `Backtest.cancel()`, via the decomposed API, is how to stop it.

## [0.11.0] — 2026-08-12

### Added ✨

- **Strategy validation and lookup.** Both entry points (`QTSurfer` and `AuthenticatedClient`) gain:
  - `validateStrategy(strategyId)` → `ValidationOutcome` — asks the platform to instantiate the
    compiled class and drive it through a bounded synthetic series, so a wiring fault surfaces
    before the first backtest. The call is idempotent: it either queues a check or queues nothing
    because the current compilation is already accounted for. `ValidationOutcome` is a sealed type
    that keeps those two answers apart — `ValidationOutcome.Queued` (this call started a check;
    carries the strategy id you passed in) and `ValidationOutcome.NotQueued` (this call started
    nothing; carries the `StrategyState` the platform holds).
  - `strategyState(strategyId)` → `StrategyState` — the platform's record of a registered strategy:
    validation verdict, `detail`, engine `notices`, and the market data the compiled class needs.
    Note this returns the api-client's `StrategyState`, not the SDK's `Strategy` handle. A verdict
    is superseded by recompilation: when `compiledAt` is later than `validatedAt`, the recorded
    verdict describes bytecode that is no longer what would run.
  - `ValidationOutcome`, a sealed interface with the `Queued` / `NotQueued` records described
    above, plus a `queued()` flag and `strategyId()` on both. Reading the outcome off the HTTP
    status lives in `com.qtsurfer.api.sdk.internal.ValidationOutcomes` — callers outside this
    library are not expected to touch `…sdk.internal`, but the type is reachable, so it is named
    here rather than treated as invisible.

  **`NotQueued` does not mean a verdict exists.** Whether this call queued work and whether there
  is a verdict are two independent questions: a `NotQueued` state can itself be `pending`, from a
  check an earlier call — possibly another process — started and that has not answered. Either
  way, read the verdict with `strategyState(...)`, polling until `validation` leaves `pending`,
  bounded by your own deadline: a queued check can stall (`validationStalled`) and is not
  guaranteed to resolve. This SDK ships no polling helper.

  `validation: passed` is a floor, not a guarantee — it means the class loaded and survived the
  first event of a short synthetic run, not that the strategy is safe to run. `dryRunIncomplete`
  means the check did not even finish its budget, so an empty `notices` list is not a clean bill
  of health.

- **Per-segment instrument listing.** `instruments(exchangeId, segment)` on both entry points —
  lists one market segment (`spot` or `futures`) of an exchange. Unwraps the same HAL envelope as
  the existing `instruments(exchangeId)`, which stays the default-segment (`spot`) shortcut, and
  returns `List<InstrumentDetail>`.

## [0.10.0] — 2026-08-12

### Changed 🔄

- Bumped `com.qtsurfer:api-client-java` to `0.8.0` (OpenAPI spec `0.106.0`, sweep walk-forward
  validation and a new sweep sensitivity endpoint) — no SDK-surface change, sweep is not yet
  exposed by this SDK.

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
