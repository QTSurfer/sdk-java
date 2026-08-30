# Backtests and parameter sweeps

## Backtest workflow

`executeBacktest(request)` compiles the source, prepares the requested window, submits execution, polls
both asynchronous stages, and completes with `ResultMap`. Polling uses Failsafe exponential backoff
and optional stage timeouts.

```java
ResultMap result = qts.executeBacktest(BacktestRequest.builder()
        .strategy(source)
        .exchangeId("binance")
        .instrument("BTC/USDT")
        .from("2026-04-13T00:00:00Z")
        .to("2026-04-14T00:00:00Z")
        .build()).join();
```

For progress, cancellation, or compiled-strategy reuse, use the decomposed API:

```java
Strategy strategy = qts.compile(request).join();
Backtest job = strategy.executeBacktest(request, BacktestOptions.builder().build()).join();
job.progress().subscribe(/* Flow.Subscriber<BacktestProgress> */);
ResultMap result = job.await().join();
```

`Backtest.cancel()` stops SDK polling and makes a best-effort backend cancellation. Cancelling the
`CompletableFuture` returned by the shortcut only stops the caller's wait; it does not cancel a
server-side run.

## Read an existing result

Use `getBacktestResult(exchangeId, jobId)` for a run submitted by another client or an earlier process.
It is a one-time state read, not a poll.

```java
import com.qtsurfer.api.sdk.BacktestOutcome;

BacktestOutcome outcome = qts.getBacktestResult("binance", jobId);
if (outcome instanceof BacktestOutcome.Completed completed) {
    System.out.println(completed.results().getPnlTotal());
}
```

A failed or cancelled run is an outcome value here, not an exception. Unknown job ids still fail.

## Equity curves

For a plain backtest, select the stored curve representation when building the request. The SDK
passes `EquityCurveOptions` through to execution; the result carries the curve inline when the
strategy emits at least one trade.

```java
import com.qtsurfer.api.client.model.EquityCurveOptions;
import com.qtsurfer.api.client.model.EquityCurveOutMode;
import com.qtsurfer.api.client.model.EquityCurveResult;

ResultMap result = qts.executeBacktest(BacktestRequest.builder()
        .strategy(source)
        .exchangeId("binance")
        .instrument("BTC/USDT")
        .from("2026-04-13T00:00:00Z")
        .to("2026-04-14T00:00:00Z")
        .equityCurve(new EquityCurveOptions()
                .resample(500)
                .differential(true)
                .outMode(EquityCurveOutMode.SHORT))
        .build()).join();

EquityCurveResult curve = result.getEquityCurve();
```

Read the returned curve according to `curve.getMeta().getOutMode()`, not the requested option: the
platform may select a compact representation. `ARRAY` supplies `getPoints()`; `SHORT` supplies
parallel `getTimestamps()` and `getEquities()` lists. The complete shared contract — transforms,
differential decoding, metadata, and the meaning of equity — is in the API's [equity-curve
guide](https://qtsurfer.github.io/docs/equity_curves.html).

Sweep rows retain curves only when requested. Configure retention with `EquityCurveRequest` and
read a retained trial with `getSweepRunEquityCurve`:

For a bounded, SDK-normalized curve, prefer `getBoundedSweepRunEquityCurve`. It always asks the
server for compact differential data, restores absolute points, defaults to 1,000 points and
allows at most 10,000. The platform may apply a lower plan-specific ceiling.

```java
BoundedEquityCurve curve = qts.getBoundedSweepRunEquityCurve(
        "binance", requestId, sweepId, runIx, null);
List<EquityCurvePoint> points = curve.points();
```

The raw method remains available below when callers explicitly need the generated API shape:

```java
import com.qtsurfer.api.client.model.EquityCurveRequest;

SweepRequest request = SweepRequest.builder()
        .strategy(source)
        .exchangeId("binance")
        .instrument("BTC/USDT")
        .from("2026-01-01T00:00:00Z")
        .to("2026-02-01T00:00:00Z")
        .param("rsi.period", ParamAxis.range(7, 28, 1))
        .objective(SweepObjective.SHARPE)
        .equityCurve(new EquityCurveRequest()
                .mode(EquityCurveRequest.ModeEnum.TOP_N)
                .n(5)
                .resample(500)
                .outMode(EquityCurveOutMode.SHORT))
        .build();

EquityCurveResult curve = qts.getSweepRunEquityCurve(
        "binance", sweep.requestId(), sweep.id(), runIx,
        EquityCurveOutMode.SHORT, 500, true);
```

Only retained rows have a curve pointer; requesting another trial returns `404`. Pass `null` for a
read-time transform argument to inherit the corresponding sweep default. The returned `meta`
describes the actual response and remains authoritative.

## Parameter sweeps

`sweep(request)` follows the same compile and prepare stages, then runs one trial per parameter
vector. A repeated preparation is safe because preparing the same window is idempotent.

```java
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
ExecuteSweepResult results = sweep.await().join();
```

The default leaderboard is plateau-ranked: it favors robust neighbourhoods over isolated objective
spikes. Request `SweepRanking.RAW` for raw objective order. `SweepOrder.NATURAL` returns every
available row in deterministic `runIx` order; the ranked view may be capped and reports that via
`getTruncated()`.

`PARTIAL` is terminal: some shards failed and their rows are absent. `Sweep.cancel()` requests a
stop between vectors, preserves completed rows, and resolves `await()` with the cancelled result.

## Walk-forward and sensitivity

`WalkForwardSpec.of(folds, trainPercent)` re-optimizes each fold on its training window and scores
the winner out of sample. Its rows identify folds, not a parameter-grid position. Use
`sweep.getSensitivity()` to inspect marginal and pairwise objective movement; pairwise heatmaps can be
capped, signalled by `getHeatmapsTruncated()`.

## Errors

All SDK failures extend `QTSError` and asynchronous workflows expose them as the cause of a
`CompletionException`. Notable subclasses are `QTSStrategyCompileError`, `QTSPreparationError`,
`QTSExecutionError`, `QTSTimeoutError`, `QTSCanceledError`, and `QTSDownloadError`.
