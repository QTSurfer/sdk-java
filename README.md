<h1 align="center">QTSurfer SDK · Java</h1>

<p align="center">
  <a href="https://github.com/QTSurfer/sdk-java/actions/workflows/ci.yml"><img src="https://github.com/QTSurfer/sdk-java/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <a href="https://jitpack.io/#com.qtsurfer/sdk-java"><img src="https://jitpack.io/v/com.qtsurfer/sdk-java.svg" alt="JitPack"></a>
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

Where `com.qtsurfer:api-client-java` gives you one method per endpoint, this package adds **workflow orchestration**, **normalized errors**, and **cancellation** — run a backtest with a single `CompletableFuture`.

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
AuthenticatedClient qts = QTSurfer.auth();
// Or: AuthenticatedClient qts = QTSurfer.auth("ak_...");

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
| `QTSURFER_APIKEY` | API key consumed by `QTSurfer.auth()` when no arg is passed |

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

var qts = QTSurfer.auth(null,
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
`await()`, and `cancel()` (best-effort server-side `cancelExecution`).

## What `backtest()` does

Orchestrates the four-step workflow exposed by the raw API:

1. **Compile** the strategy (`POST /strategy` in async mode) and poll `GET /strategy/{jobId}` until completed.
2. **Prepare** the data range (`POST /backtest/{exchange}/ticker/prepare`) and poll `GET …/prepare/{jobId}` until `Completed`.
3. **Execute** the backtest (`POST /backtest/{exchange}/ticker/execute`) and poll `GET …/execute/{jobId}` until `Completed`.
4. Resolve the returned `CompletableFuture` with the `ResultMap` (`pnlTotal`, `totalTrades`, `sharpeRatio`, `signalsUrl`, …).

Polling uses Failsafe `RetryPolicy` with exponential backoff (initial → max, capped) plus an optional `Timeout` per stage.

Progress is emitted:

- On every stage transition (`percent == null`).
- After each poll where the backend reports `size > 0` (`percent` in 0–100).

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

List available exchanges and the instruments (with data-availability windows) for a given exchange.

```java
import com.qtsurfer.api.client.model.Exchange;
import com.qtsurfer.api.client.model.InstrumentDetail;

// List exchanges
List<Exchange> exchanges = qts.exchanges();
exchanges.forEach(e -> System.out.println(e.getId() + " — " + e.getName()));
// → binance — Binance
// → binancefutures — Binance Futures

// List instruments for an exchange
List<InstrumentDetail> instruments = qts.instruments("binance");
instruments.forEach(i -> System.out.printf(
        "%s  data: %s → %s  last: %.2f%n",
        i.getId(), i.getDataFrom(), i.getDataTo(), i.getLastPrice()));
```

HTTP errors surface as `QTSError`. Responses reflect live platform state — no client-side cache.

## Error hierarchy

All SDK errors extend `QTSError` (a `RuntimeException`) and surface as the cause of the `CompletionException` wrapping them when the future fails.

```java
try {
    qts.backtest(req).join();
} catch (CompletionException e) {
    Throwable cause = e.getCause();
    switch (cause) {
        case QTSStrategyCompileError x -> log.error("Compile failed: {}", x.getMessage());
        case QTSPreparationError x     -> log.error("Data prep failed: {}", x.getMessage());
        case QTSExecutionError x       -> log.error("Execution failed: {}", x.getMessage());
        case QTSDownloadError x        -> log.error("Download failed: {}", x.getMessage());
        case QTSTimeoutError x         -> log.error("Stage timed out: {}", x.getMessage());
        case QTSCanceledError x        -> log.error("Canceled");
        default                        -> throw e;
    }
}
```

## Cancellation

Two ways to cancel an in-flight backtest:

```java
// 1. Cancel the future returned by the backtest() shortcut.
CompletableFuture<ResultMap> future = qts.backtest(req, opts);
future.cancel(true);

// 2. Cancel through the Backtest handle (decomposed API).
Backtest job = strategy.backtest(req, opts).join();
job.cancel();
```

Both stop polling immediately and, if the execute stage has already started
server-side, best-effort call `cancelExecution` on the backend.

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

### v0.1 — Core workflow ✅

- [x] `QTSurfer` client over `com.qtsurfer:api-client`
- [x] `qts.backtest()` orchestrating compile → prepare → execute
- [x] Backoff, timeout, and cancellation via Failsafe policies
- [x] `QTSError` hierarchy

### v0.2 — Domain objects + binary downloads ✅

- [x] `Strategy` + `Backtest` handles with `id()`, `state()`, `progress()`, `await()`, `cancel()`
- [x] Progress exposed as `Flow.Publisher<BacktestProgress>` (JDK reactive-streams)
- [x] Hourly tickers/klines downloads (`qts.tickers(...)` / `qts.klines(...)`) with `DownloadFormat` (Lastra/Parquet)

### v0.3 — Exchange & instrument discovery ✅

- [x] `qts.exchanges()` → `List<Exchange>` (live, no cache)
- [x] `qts.instruments(exchangeId)` → `List<InstrumentDetail>` with data-availability windows, last price, and 24 h volume

### v0.4 — Ecosystem

- [ ] TTL cache for `exchanges` / `instruments`
- [ ] Loaders for `signalsUrl` Parquet into `duckdb-java` / `lastra-java`
- [ ] Optional reactive adapters (Reactor / RxJava)

## License

Apache-2.0 — see [LICENSE](./LICENSE).
