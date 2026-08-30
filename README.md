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

<p align="center"><code>com.qtsurfer:sdk-java</code></p>

---

Where `com.qtsurfer:api-client-java` gives one method per endpoint, this package adds workflow
orchestration, normalized errors, cancellation, and authenticated-session management. It uses the
JDK HTTP client, Failsafe for polling and retry, and SLF4J 2.x without shipping a logging binding.

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

The generated API client and Failsafe are transitive dependencies. Maven Central support is planned
under the same coordinate.

## Quick start

`QTSurfer.authenticate()` reads `QTSURFER_APIKEY`, obtains a JWT, and refreshes it when needed.

```java
import com.qtsurfer.api.client.model.ResultMap;
import com.qtsurfer.api.sdk.BacktestRequest;
import com.qtsurfer.api.sdk.QTSurfer;
import com.qtsurfer.api.sdk.auth.AuthenticatedClient;

import java.nio.file.Files;
import java.nio.file.Path;

AuthenticatedClient qts = QTSurfer.authenticate();

ResultMap result = qts.executeBacktest(BacktestRequest.builder()
        .strategy(Files.readString(Path.of("Strategy.java")))
        .exchangeId("binance")
        .instrument("BTC/USDT")
        .from("2026-04-13T00:00:00Z")
        .to("2026-04-14T00:00:00Z")
        .storeSignals(true)
        .build()).join();

System.out.println("PnL: " + result.getPnlTotal());
```

## Guides

The README is an entry point; the source documentation follows the API's functional sections.

| API section | SDK guide |
| --- | --- |
| Authentication | [docs/auth.md](docs/auth.md) |
| Exchanges, instruments, and downloads | [docs/market_data.md](docs/market_data.md) |
| Backtests, parameter sweeps, and equity curves | [docs/backtesting.md](docs/backtesting.md) |
| Equity-curve format and transforms | [API guide](https://qtsurfer.github.io/docs/equity_curves.html) |
| Strategies and validation | [docs/strategy.md](docs/strategy.md) |
| Writing Java strategy source | [API guide](https://qtsurfer.github.io/docs/strategy_coding.html) |
| Caller-uploaded datasets | [docs/datasets.md](docs/datasets.md) |
| Complete operation mapping | [docs/api-coverage.md](docs/api-coverage.md) |

## Development

| Command | Description |
| --- | --- |
| `mvn verify` | Compile, run unit tests, and build jar, sources, and Javadoc |
| `mvn -B -Dtest='*IntegrationTest' test` | Run live integration tests; requires `JWT_API_TOKEN` and `QTSURFER_API_URL` |
| `mvn clean` | Remove `target/` |

Set `QTSURFER_TEST_VERBOSE=1` to emit live-test progress through SLF4J.

## Roadmap

- [ ] TTL cache for `getExchanges` / `getInstruments`
- [ ] Loaders for `signalsUrl` Parquet into `duckdb-java` / `lastra-java`
- [ ] Optional reactive adapters (Reactor / RxJava)

## License

Apache-2.0 — see [LICENSE](./LICENSE).
