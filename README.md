<h1 align="center">QTSurfer SDK · Java</h1>

<p align="center">
  <a href="https://github.com/QTSurfer/sdk-java/actions/workflows/ci.yml"><img src="https://github.com/QTSurfer/sdk-java/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <a href="https://jitpack.io/#QTSurfer/sdk-java"><img src="https://jitpack.io/v/QTSurfer/sdk-java.svg" alt="JitPack"></a>
  <img src="https://img.shields.io/badge/JDK-17%2B-blue?logo=openjdk&logoColor=white" alt="JDK 17+">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0-blue.svg" alt="License"></a>
</p>

<p align="center">
  Opinionated Java SDK for <a href="https://qtsurfer.com">QTSurfer</a>, built on top of <a href="https://github.com/QTSurfer/api-client-java">net.qtsurfer:api-client</a>.
</p>

<p align="center">
  <code>net.qtsurfer:sdk</code> · <code>com.github.QTSurfer:sdk-java</code>
</p>

---

Where `net.qtsurfer:api-client` gives you one method per endpoint, this package adds **workflow orchestration**, **normalized errors**, and **cancellation** — run a backtest with a single `CompletableFuture`.

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
  <groupId>com.github.QTSurfer</groupId>
  <artifactId>sdk-java</artifactId>
  <version>v0.1.0</version>
</dependency>
```

The transitive `com.github.QTSurfer:api-client-java` and `dev.failsafe:failsafe` come along automatically.

### Maven Central (future)

Once published to Central, the coordinate will be `net.qtsurfer:sdk:0.1.0`.

## Quick start

```java
import net.qtsurfer.api.client.model.ResultMap;
import net.qtsurfer.api.sdk.BacktestOptions;
import net.qtsurfer.api.sdk.BacktestRequest;
import net.qtsurfer.api.sdk.QTSurfer;

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
import net.qtsurfer.api.sdk.DownloadFormat;

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

Cancel the returned `CompletableFuture` to stop polling. If the execute stage has already started server-side, the SDK best-effort calls `cancelExecution` on the backend.

```java
CompletableFuture<ResultMap> future = qts.backtest(req, opts);
// elsewhere:
future.cancel(true);
```

## Under the hood

- [`dev.failsafe:failsafe`](https://failsafe.dev) — retry policies with exponential backoff, optional per-stage `Timeout`, `withInterrupt()` so thread interruption from `CompletableFuture#cancel(true)` propagates cleanly.
- [`net.qtsurfer:api-client`](https://github.com/QTSurfer/api-client-java) — generated with openapi-generator's `native` library; uses `java.net.http.HttpClient`, so no OkHttp/Apache HttpClient transitive dependency.
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

- [x] `QTSurfer` client over `net.qtsurfer:api-client`
- [x] `qts.backtest()` orchestrating compile → prepare → execute
- [x] Backoff, timeout, and cancellation via Failsafe policies
- [x] `QTSError` hierarchy

### v0.2 — Domain objects

- [ ] `Strategy` + `BacktestJob` classes with methods like `wait()`, `cancel()`, `stream()`
- [ ] TTL cache for `exchanges` / `instruments`

### v0.3 — Streaming progress

- [ ] Progress exposed as `Flow.Publisher<BacktestProgress>` (JDK reactive-streams)

### v0.4 — Ecosystem

- [ ] Loaders for `signalsUrl` Parquet into `duckdb-java` / `lastra-java`
- [ ] Optional reactive adapters (Reactor / RxJava)

## License

Apache-2.0 — see [LICENSE](./LICENSE).
