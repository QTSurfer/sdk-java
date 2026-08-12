<h1 align="center">QTSurfer SDK · Java</h1>

<p align="center">
  <a href="https://github.com/QTSurfer/sdk-java/actions/workflows/ci.yml"><img src="https://github.com/QTSurfer/sdk-java/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <a href="https://jitpack.io/#com.qtsurfer/sdk-java"><img src="https://jitpack.io/v/com.qtsurfer/sdk-java.svg" alt="JitPack"></a>
  <a href="https://qtsurfer.github.io/sdk-java/"><img src="https://img.shields.io/badge/docs-javadoc-blue" alt="Javadoc"></a>
  <img src="https://img.shields.io/badge/JDK-17%2B-blue?logo=openjdk&logoColor=white" alt="JDK 17+">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0-blue.svg" alt="License"></a>
</p>

<p align="center">
  Opinionated Java SDK for <a href="https://qtsurfer.com">QTSurfer</a>, built on top of <a href="https://github.com/QTSurfer/api-client-java">com.qtsurfer:api-client</a>.
</p>

<p align="center">
  <code>com.qtsurfer:sdk-java</code>
</p>

---

Where `com.qtsurfer:api-client-java` gives you one method per endpoint, this package adds **workflow orchestration**, **normalized errors**, and **cancellation** — run a backtest, or a whole parameter sweep, from a single call. What it reaches of the API is listed in [API coverage](#api-coverage).

- Powered by [`java.net.http.HttpClient`](https://docs.oracle.com/en/java/javase/17/docs/api/java.net.http/java/net/http/HttpClient.html) (JDK built-in) via the transitive client.
- Retry/backoff/timeout delegated to [Failsafe](https://failsafe.dev) — no hand-rolled polling loops.
- SLF4J 2.x API (no binding shipped — consumers bring their own).
- **JDK 17+**.

## Installation

### JitPack

```xml
<repositories>
  <repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
  </repository>
</repositories>

<dependency>
  <groupId>com.qtsurfer</groupId>
  <artifactId>sdk-java</artifactId>
  <version>x.x.x</version>
</dependency>
```

The transitive `com.qtsurfer:api-client-java` and `dev.failsafe:failsafe` come along automatically.

### Maven Central (future)

Once published to Central, the coordinate will be `com.qtsurfer:sdk-java:x.x.x`.

## Quick start

One call: API key in, ready-to-use session out. JWT refresh on 401 is
handled for you.

```java
import com.qtsurfer.api.client.model.ResultMap;
import com.qtsurfer.api.sdk.BacktestRequest;
import com.qtsurfer.api.sdk.QTSurfer;
import com.qtsurfer.api.sdk.auth.AuthenticatedClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

// Reads QTSURFER_APIKEY from env when no argument is passed.
AuthenticatedClient qts = QTSurfer.authenticate();
// Or: AuthenticatedClient qts = QTSurfer.authenticate("ak_...");

ResultMap result = qts.backtest(
        BacktestRequest.builder()
                .strategy(Files.readString(Path.of("Strategy.java")))
                .exchangeId("binance")
                .instrument("BTC/USDT")
                .from("2026-04-13T00:00:00Z")
                .to("2026-04-14T00:00:00Z")
                .storeSignals(true)
                .build()).join();

System.out.println("PnL: " + result.getPnlTotal());
System.out.println("Trades: " + result.getTotalTrades());
```

### Environment

| Variable          | Purpose                                              |
| ----------------- | ---------------------------------------------------- |
| `QTSURFER_APIKEY` | API key consumed by `QTSurfer.authenticate()` when no arg is passed |

### Pluggable token storage

Tokens are kept in memory by default. Implement `TokenStore` to back
tokens by an on-disk file, a secret manager, or a desktop keychain:

```java
import com.qtsurfer.api.client.model.AuthTokenResponse;
import com.qtsurfer.api.sdk.QTSurfer;
import com.qtsurfer.api.sdk.auth.AuthOptions;
import com.qtsurfer.api.sdk.auth.TokenStore;

TokenStore fileStore = new TokenStore() {
    @Override public AuthTokenResponse load() { /* read from disk */ return null; }
    @Override public void save(AuthTokenResponse t) { /* write to disk */ }
    @Override public void clear() { /* delete the file */ }
};

var qts = QTSurfer.authenticate(null,
        AuthOptions.builder().store(fileStore).build());
```

`AuthOptions` also accepts `baseUrl`, `httpClient`, and `executor` for
staging targets, custom transports, and dedicated thread pools.

### Lower-level: hand-managed JWT

If you already hold a JWT and want to manage refresh yourself, the
`QTSurfer.builder()` path is unchanged:

```java
import com.qtsurfer.api.sdk.QTSurfer;

QTSurfer qts = QTSurfer.builder()
        .baseUrl("https://api.qtsurfer.com/v1")
        .token(System.getenv("JWT_API_TOKEN"))
        .build();
```

### Decomposed pipeline (advanced)

```java
import com.qtsurfer.api.client.model.ResultMap;
import com.qtsurfer.api.sdk.BacktestOptions;
import com.qtsurfer.api.sdk.BacktestRequest;
import com.qtsurfer.api.sdk.QTSurfer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

QTSurfer qts = QTSurfer.builder()
        .baseUrl("https://api.qtsurfer.com/v1")
        .token(System.getenv("JWT_API_TOKEN"))
        .build();

CompletableFuture<ResultMap> future = qts.backtest(
        BacktestRequest.builder()
                .strategy(Files.readString(Path.of("Strategy.java")))
                .exchangeId("binance")
                .instrument("BTC/USDT")
                .from("2026-04-13T00:00:00Z")
                .to("2026-04-14T00:00:00Z")
                .storeSignals(true)
                .build(),
        BacktestOptions.builder()
                .onProgress(p -> System.out.printf("[%s] %s%n",
                        p.stage(),
                        p.percent() != null ? String.format("%.1f%%", p.percent()) : ""))
                .pollInterval(Duration.ofMillis(500))
                .maxPollInterval(Duration.ofSeconds(5))
                .timeout(Duration.ofMinutes(10))
                .build());

ResultMap result = future.join();
System.out.println("PnL: " + result.getPnlTotal());
System.out.println("Trades: " + result.getTotalTrades());
```

### Decomposed: reuse a compiled `Strategy` across runs

`qts.backtest(req)` is a shortcut for `compile → backtest → await`. When you want
the intermediate handles — to reuse a compiled strategy, subscribe to progress as
a reactive stream, or cancel mid-run — use them directly:

```java
import com.qtsurfer.api.sdk.Backtest;
import com.qtsurfer.api.sdk.Strategy;

Strategy strategy = qts.compile(request).join();
Backtest job = strategy.backtest(request, options).join();

job.progress().subscribe(/* a Flow.Subscriber<BacktestProgress> */);

ResultMap result = job.await().join();
```

`Backtest` exposes `id()`, `state()`, `progress()` (a `Flow.Publisher<BacktestProgress>`),
`await()`, and `cancel()` (best-effort server-side `cancelBacktest`).

## API coverage

Measured against **API spec 0.107.0**: 18 operations, all 18 reachable from this SDK.

It exists because the generated `com.qtsurfer:api-client-java` tracks the spec automatically and
this hand-written layer does not. Unqualified method names below are on both entry points,
`QTSurfer` and `AuthenticatedClient`, whose surfaces mirror each other; `authenticate` is the
exception, since it is the auth path itself.

| Operation | Reached by |
| --- | --- |
| `authenticate` | Direct — `QTSurfer.authenticate()`; `AuthenticatedClient.refresh()` forces a re-mint |
| `listExchanges` | Direct — `exchanges()` |
| `listInstruments` | Direct — `instruments(exchangeId)` |
| `listSegmentInstruments` | Direct — `instruments(exchangeId, segment)` |
| `downloadTickers` | Direct — `tickers(...)` |
| `downloadKlines` | Direct — `klines(...)` |
| `compileStrategy` | Direct — `compile(...)` → `Strategy` |
| `validateStrategy` | Direct — `validateStrategy(strategyId)` → `ValidationOutcome` |
| `getStrategy` | Direct — `strategyState(strategyId)` → `StrategyState` |
| `prepareBacktest` | Via workflow — `backtest(...)` and `sweep(...)` |
| `getPrepareStatus` | Via workflow — the prepare poll |
| `executeBacktest` | Via workflow — `backtest(...)` |
| `getBacktestResult` | Via workflow (the execute poll behind `Backtest.await()`) and direct — `backtestResult(exchangeId, jobId)` reads a run this process did not start |
| `cancelBacktest` | Direct — `Backtest.cancel()` |
| `executeSweep` | Via workflow — `sweep(...)` |
| `getSweepResult` | Via workflow (the leaderboard poll) and direct — `Sweep.results(...)` re-reads it under another view |
| `cancelSweep` | Direct — `Sweep.cancel()` |
| `getSweepSensitivity` | Direct — `Sweep.sensitivity(...)` |

**"Via workflow"** means the operation runs as a stage of `backtest(...)`, `sweep(...)`, or the
decomposed `Strategy.backtest(...)`, and has no standalone method — deliberately. Those calls own
the dataset lifecycle: preparing answers with the job id of the prepared window, and that id is
what the execute and sweep endpoints then consume. A standalone `prepare()` would hand it to the
caller to hold and pass on to every later call. Preparing is idempotent — the same instrument and
window always resolve to the same job — so a workflow that prepares on every call duplicates no
work, and the id never has to leave it. Not a gap.

The two rows reading **"via workflow … and direct"** are not exceptions to that. Reading a
leaderboard, or the result of a run that already exists, is not a stage of a run you are
performing: it is a query against a resource the platform already holds, addressed by ids you were
handed rather than ids a workflow has to keep alive. That is why `Sweep.results(...)` and
`backtestResult(...)` are standalone while `prepare()` and `executeBacktest()` are not.

**Maintenance contract.** When the spec gains an operation, it gains a row here. If this layer
deliberately does not wrap it, the row says so and why.

## What `backtest()` does

Orchestrates the four-step workflow exposed by the raw API:

1. **Compile** the strategy (`POST /strategy`), which answers synchronously with the `strategyId`.
2. **Prepare** the data range (`POST /backtest/{exchange}/ticker/prepare`) and poll `GET …/prepare/{jobId}` until `Completed`.
3. **Execute** the backtest (`POST /backtest/{exchange}/ticker/execute`) and poll `GET …/execute/{jobId}` until `Completed`.
4. Resolve the returned `CompletableFuture` with the `ResultMap` (`pnlTotal`, `totalTrades`, `sharpeRatio`, `signalsUrl`, …).

Polling uses Failsafe `RetryPolicy` with exponential backoff (initial → max, capped) plus an optional `Timeout` per stage.

Progress is emitted:

- On every stage transition (`percent == null`).
- After each poll where the backend reports `size > 0` (`percent` in 0–100).

### Reading a run you did not start

The `Backtest` handle only exists in the process that submitted the run. When a job id reaches you
from somewhere else — another client, another session, the same process before a restart — ask the
platform directly:

```java
import com.qtsurfer.api.sdk.BacktestOutcome;

BacktestOutcome outcome = qts.backtestResult("binance", "5f3c…");

if (outcome instanceof BacktestOutcome.Completed c) {
    System.out.println("PnL: " + c.results().getPnlTotal());
} else if (outcome instanceof BacktestOutcome.Failed f) {
    System.out.println("Failed: " + f.state().getStatusDetail());
} else if (outcome instanceof BacktestOutcome.Aborted) {
    System.out.println("Cancelled");
} else {
    System.out.println("Still running; ask again later");
}
```

One read, nothing submitted. **A run that ended badly is an answer here, not an exception** — the
opposite of `Backtest.await()`, which raises on a failed or aborted run. Someone waiting for a
result they asked for is not getting one; someone asking *what happened to this job* is, so
`BacktestOutcome` carries the four cases (finished, failed, cancelled, not yet) as values and only
an id the platform does not recognise raises. `finished()` collapses the first three when the
distinction does not matter.

`exchangeId` is required and is not defaulted: a run's result is addressed under the exchange it
was submitted against, so a job id on its own does not identify it. To *wait* for a run you started
yourself, use `Backtest.await()` instead — this is a snapshot and does not poll.

## Strategy validation

Before spending a backtest on it, ask the platform to check that a compiled strategy can actually
run: the class is instantiated and driven through a bounded synthetic series, so a wiring fault
surfaces here instead of on your first run.

The call is idempotent: it either queues a check, or queues nothing because the current compilation
is already accounted for. `ValidationOutcome` keeps those two answers apart.

```java
import com.qtsurfer.api.sdk.ValidationOutcome;

Strategy strategy = qts.compile(source).join();

ValidationOutcome outcome = qts.validateStrategy(strategy.id());

if (outcome instanceof ValidationOutcome.NotQueued nq) {
    // This call started nothing — `nq.state()` is what the platform already holds.
    log.info("Nothing queued; state is {}", nq.state().getValidation());
} else {
    // This call started a check. Nothing to read yet.
    log.info("Queued a check for {}", outcome.strategyId());
}
```

`ValidationOutcome` is a sealed interface permitting exactly `Queued` and `NotQueued`, so on Java 21
and later you can `switch` over it exhaustively without a `default`.

**`NotQueued` does not mean a verdict exists.** These are two independent questions:
`outcome.queued()` answers *did this call start work?*, and `validation` answers *is there a verdict
right now?* A `NotQueued` state can itself be `pending` — a check that an earlier call, possibly
from another process, already started and that has not answered.

So read the verdict either way:

```java
import com.qtsurfer.api.client.model.StrategyState;

StrategyState state = qts.strategyState(strategy.id());

switch (state.getValidation()) {
    case PASSED        -> log.info("Loaded and survived its first event");
    case FAILED        -> log.error("Validation failed: {}", state.getDetail());
    case PENDING       -> log.warn("Still running — re-read later, this is not a verdict");
    case NOT_VALIDATED -> log.warn("Registered but never checked");
}
// `notices` is absent, not empty, when the run surfaced nothing.
if (state.getNotices() != null) {
    state.getNotices().forEach(n -> log.info("{} {} — {}", n.getLevel(), n.getCode(), n.getMessage()));
}
```

Re-read on your own schedule until `validation` leaves `pending`, bounded by a deadline of your own:
a queued check can stall (`validationStalled`) and is not guaranteed to resolve. The SDK ships no
polling helper.

**`passed` is a floor, not a guarantee.** It means the class loaded and survived the first event of
a short synthetic run — not that the strategy is correct, and not that it is safe to run. When
`dryRunIncomplete` is `true` the check did not even finish its budget, so an empty `notices` list is
not a clean bill of health.

`qts.strategyState(strategyId)` also reports what market data the compiled class needs
(`requiredSources`) and when the live compilation was produced (`compiledAt`). A verdict is
superseded by recompilation: when `compiledAt` is later than `validatedAt`, the recorded verdict
describes bytecode that is no longer what would run — call `validateStrategy` again. HTTP errors
surface as `QTSError`; a `404` means only that no such strategy is registered for you.

## Parameter sweeps

Run the same strategy once per parameter vector over one prepared window, and read the
leaderboard. One call absorbs the same stages as `backtest()` — compile → prepare → submit —
because the sweep endpoint is addressed by the id of an already-prepared dataset; preparing is
idempotent, so sweeping the same window twice prepares it once.

```java
import com.qtsurfer.api.sdk.*;
import com.qtsurfer.api.client.model.ExecuteSweepResult;

SweepRequest request = SweepRequest.builder()
    .strategy(source)
    .exchangeId("binance")
    .instrument("BTC/USDT")
    .from("2026-01-01T00:00:00Z")
    .to("2026-02-01T00:00:00Z")
    .param("rsiPeriod", ParamAxis.range(7, 28, 1))
    .param("useTrendFilter", ParamAxis.of(true, false))
    .objective(SweepObjective.SHARPE)
    .build();

Sweep sweep = qts.sweep(request).join();

// Available immediately, before a single trial has run:
sweep.accepted().getSeed();      // effective seed — resubmit it to replay a sampled sweep
sweep.accepted().getQueued();    // false: an identical sweep already existed, nothing was enqueued
sweep.accepted().getTotalRuns(); // size of the expanded grid

ExecuteSweepResult result = sweep.await().join();
```

`Sweep` exposes `id()`, `requestId()`, `strategyId()`, `accepted()`, `state()`, `progress()`
(a `Flow.Publisher<SweepProgressEvent>`), `await()`, `results(...)`, `cancel()`, and
`sensitivity()`.

### Reading the leaderboard

**The default order is not the raw objective order.** It is *plateau* order: a point's plateau
score is the objective of the worst run in its immediate neighbourhood, so a point ranks well only
if the region around it does too — the highest raw score is very often a spike that does not
survive the parameters moving slightly. Pass `SweepOptions.builder().ranking(SweepRanking.RAW)` for
the unadjusted order.

What you asked for is not always what was applied: read `result.getRanking()`. A sweep with no
stored parameter grid cannot be plateau-ranked and falls back to raw.

- **`plateauScore` and `neighbourCount` are read together.** `neighbourCount == 0` means the point
  had no neighbours to compare against, so its plateau score is *unevidenced*, not confirmed.
- **`deflatedSharpe`** — probability that a row's Sharpe reflects real edge rather than the best
  draw from however many vectors were tried. Absent on aborted runs and on sweeps with too few
  trials to deflate against.
- **`pbo`** — probability of backtest overfitting for the sweep as a whole. Above ~0.5 the sweep is
  selecting noise, which discredits the top row however good it looks. Absent while the sweep is
  still running and on sweeps too small for the statistic to mean anything.
- **`PARTIAL` is a terminal status**, not a "still going" one: at least one unit of work died and
  its runs are missing. There is no sweep-wide failed status, so a sweep whose every shard died is
  `PARTIAL` with `leaderboardSize == 0`.

### Every row, not just the top of the board

The ranked view is a *display* view: sorted and capped, with `result.getTruncated()` reporting when
the cap bit. `SweepOrder.NATURAL` returns every available row instead, untruncated, in
deterministic `runIx` order — the view to read when materialising durable trial rows, and the route
to rows the ranked view dropped.

The view is a query parameter on the *read*, so switching it costs one request. `Sweep.results(...)`
re-reads the sweep you already have — no compile, no prepare, no second sweep:

```java
ExecuteSweepResult ranked = sweep.await().join();

if (Boolean.TRUE.equals(ranked.getTruncated())) {
    ExecuteSweepResult all = sweep.results(SweepOrder.NATURAL);   // every row, one GET
}
```

`sweep.results(order, ranking)` takes both; `null` means the platform default for either. Use
`SweepOptions.order(...)` instead when you want the *polled* view — the one `await()` resolves
with — to be the natural one from the start.

Works on a sweep still in flight too, returning the rows finished so far.

`ranking` is **ignored** on the natural view: its order is always `runIx`, and the response comes
back `raw`. Rank, plateau score and neighbour count belong to the ranked view and are not part of
it.

### Walk-forward

Attaching `walkForward` changes what the sweep does, not just how much of it runs: the data is cut
into sequential folds, each fold optimizes the whole grid on its own window and scores only its
winner on the window immediately after. It answers "does re-optimizing this periodically actually
work" rather than "which parameters won", and costs folds × grid.

```java
SweepRequest wf = SweepRequest.builder()
    // …
    .walkForward(WalkForwardSpec.of(4, 70))   // 4 folds, 70 % of each spent optimizing
    .build();
```

`result.getWalkForward()` is the discriminator, and it is present from acceptance onward — also on
`sweep.accepted().getWalkForward()` — so you can branch on the answer's shape while still polling.
Its leaderboard is one row per *completed fold*: that fold's winner as it scored out-of-sample,
with **`runIx` carrying the fold index rather than a grid position**. No plateau, DSR or PBO figure
is reported for one. **`paramDrift` absent is not zero** — the field is omitted when it could not be
computed, and zero is itself a meaningful reading (winners that never moved), so a placeholder
would be indistinguishable from perfect stability.

### Progress

`SweepProgressEvent.snapshot()` carries the platform's progress record on every `EXECUTING` event.
Two of its counts are easy to add together by mistake: `aborted` counts individual runs that
executed and aborted, while `failedShards` counts whole units of work that failed and never
reported anything — a shard that dies before producing a row leaves `aborted` at zero, which is
exactly why the second count exists. `retrying` is not a failure count either. `etaSeconds` is
*omitted, never zero* when it cannot be computed, and runs conservative when present.

### Sensitivity

How the objective moves as each parameter moves — the question a leaderboard cannot answer, since a
sweep can spend its whole budget on an axis that never moved the objective at all.

```java
SweepSensitivity s = sweep.sensitivity();               // the sweep's own objective
SweepSensitivity r = sweep.sensitivity(SweepObjective.SORTINO);

if (Boolean.TRUE.equals(s.getHeatmapsTruncated())) {
    // at least one two-parameter surface was left out — this is not the full interaction set
}
```

Marginals are always complete; the pair surfaces are quadratic in the axis count and may be capped,
which is what `heatmapsTruncated` reports. Readable while the sweep is still running, in which case
the aggregates cover the rows finished so far (`rowsAnalysed`). Aborted runs are excluded
throughout — a run that threw measured nothing.

## Hourly tickers/klines downloads

Stream one hour of raw ticker or kline data for an instrument. The default wire format is
[Lastra](https://github.com/QTSurfer/lastra-java) (`application/vnd.lastra`); pass
`DownloadFormat.PARQUET` for on-the-fly Parquet conversion.

```java
import com.qtsurfer.api.sdk.DownloadFormat;

// Lastra (default), streamed straight to disk
try (var in = qts.tickers("binance", "BTC", "USDT", "2026-01-15T10")) {
    Files.copy(in, Path.of("BTC_USDT_2026-01-15_h10.lastra"));
}

// Parquet
try (var in = qts.klines("binance", "BTC", "USDT", "2026-01-15T10", DownloadFormat.PARQUET)) {
    // feed into Apache Parquet, DuckDB, etc.
}
```

The caller closes the stream. HTTP errors surface as `QTSDownloadError` (subclass of `QTSError`).

## Exchange & instrument discovery

List available exchanges and the instruments (with per-data-type coverage windows) for a given exchange.

```java
import com.qtsurfer.api.client.model.CoverageWindow;
import com.qtsurfer.api.client.model.Exchange;
import com.qtsurfer.api.client.model.InstrumentDetail;

// List exchanges
List<Exchange> exchanges = qts.exchanges();
exchanges.forEach(e -> System.out.println(e.getId() + " — " + e.getName()));
// → binance — Binance
// → binancefutures — Binance Futures

// List instruments for an exchange
List<InstrumentDetail> instruments = qts.instruments("binance");
instruments.forEach(i -> {
    CoverageWindow window = i.getCoverage() == null
            ? null
            : i.getCoverage().getTickers() != null ? i.getCoverage().getTickers() : i.getCoverage().getKlines();
    System.out.printf("%s  data: %s → %s  last: %.2f%n",
            i.getId(),
            window == null ? "n/a" : window.getFrom().toLocalDate(),
            window == null ? "n/a" : window.getTo().toLocalDate(),
            i.getLastPrice());
});
```

`instruments(exchangeId)` is the default-segment shortcut and lists `spot`. Pass a segment
explicitly to list another one:

```java
List<InstrumentDetail> perps = qts.instruments("binancefutures", "futures");
```

Both overloads unwrap the same HAL envelope and return `List<InstrumentDetail>`.

HTTP errors surface as `QTSError`. Responses reflect live platform state — no client-side cache.

## Error hierarchy

All SDK errors extend `QTSError` (a `RuntimeException`) and surface as the cause of the `CompletionException` wrapping them when the future fails.

```java
try {
    qts.backtest(req).join();
} catch (CompletionException e) {
    Throwable cause = e.getCause();
    if (cause instanceof QTSStrategyCompileError x) log.error("Compile failed: {}", x.getMessage());
    else if (cause instanceof QTSPreparationError x) log.error("Data prep failed: {}", x.getMessage());
    else if (cause instanceof QTSExecutionError x)   log.error("Execution failed: {}", x.getMessage());
    else if (cause instanceof QTSDownloadError x)    log.error("Download failed: {}", x.getMessage());
    else if (cause instanceof QTSTimeoutError x)     log.error("Stage timed out: {}", x.getMessage());
    else if (cause instanceof QTSCanceledError)      log.error("Canceled");
    else throw e;
}
```

Written as an `instanceof` chain because this SDK targets JDK 17, where pattern matching for
`switch` is still a preview feature. On 21 or newer the same branches read better as a `switch`.

## Cancellation

Cancelling an in-flight backtest goes through the `Backtest` handle:

```java
Backtest job = strategy.backtest(req, opts).join();
job.cancel();
```

That stops polling immediately and, if the execute stage has already started server-side,
best-effort calls `cancelBacktest` on the backend.

**Cancelling the future from the `backtest(...)` shortcut does not stop the run.** The shortcut
composes its stages with `thenCompose`, and cancelling a composed `CompletableFuture` does not
propagate back through the chain — so `future.cancel(true)` ends *your wait* and leaves the backtest
running server-side, still billing. This is a property of how the shortcut is built, not an
oversight to work around: if you need to stop the work rather than stop waiting for it, use the
decomposed API above, which hands you the handle.

**A sweep cancels differently, on purpose.** `Sweep.cancel()` asks the platform to stop between
parameter vectors, and the rows already scored stay readable — so the SDK keeps polling until the
sweep reports `CANCELLED` and then resolves `await()` *normally*, with the partial leaderboard,
rather than raising `QTSCanceledError`. Check `result.getStatus()`. That also means cancelling
depends on the platform answering: with no `timeout` configured, a sweep that never reports
cancelled leaves `await()` waiting.

## Under the hood

- [`dev.failsafe:failsafe`](https://failsafe.dev) — retry policies with exponential backoff, optional per-stage `Timeout`, `withInterrupt()` so thread interruption from `CompletableFuture#cancel(true)` propagates cleanly.
- [`com.qtsurfer:api-client`](https://github.com/QTSurfer/api-client-java) — generated with openapi-generator's `native` library; uses `java.net.http.HttpClient`, so no OkHttp/Apache HttpClient transitive dependency.
- `StatusNormalizer` — maps the backend's mixed-case status strings (`queued`, `started`, `completed`, `failed`, …) to a stable enum so the retry predicate and terminal checks work regardless of spec drift.

## Development

| Command | Description |
| --- | --- |
| `mvn verify` | Compile, run unit tests, build jar + sources + javadoc |
| `mvn -B -Dtest='*IntegrationTest' test` | Run the integration test — requires `JWT_API_TOKEN` |
| `mvn clean` | Remove `target/` |

### Integration test

Hits the real backend with `ForcedTradeStrategy` on `binance BTC/USDT` for the previous UTC day. Controlled by env vars:

- `JWT_API_TOKEN` — required; the test is skipped when absent.
- `QTSURFER_API_URL` — required; the test is skipped when absent.
- `QTSURFER_TEST_VERBOSE=1` — optional; stream progress events and the final result through SLF4J.

```bash
JWT_API_TOKEN=... QTSURFER_API_URL=... QTSURFER_TEST_VERBOSE=1 mvn -B -Dtest='*IntegrationTest' test
```

## Roadmap

What the SDK reaches today is the [API coverage](#api-coverage) table; which release added what is
in [CHANGELOG.md](./CHANGELOG.md). This section is only what is still missing — nothing here is a
missing API operation:

- [ ] TTL cache for `exchanges` / `instruments`
- [ ] Loaders for `signalsUrl` Parquet into `duckdb-java` / `lastra-java`
- [ ] Optional reactive adapters (Reactor / RxJava)

## License

Apache-2.0 — see [LICENSE](./LICENSE).
