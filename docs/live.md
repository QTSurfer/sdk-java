# Live execution

Live methods are available on `QTSurfer` (caller-managed JWT) and API-key-authenticated
`AuthenticatedClient` (proactive refresh and retry-on-401). For a long-running process, prefer the
authenticated client; see [auth.md](auth.md) for setup and token ownership.

Live execution runs a compiled strategy continuously. It is separate from a backtest: start it deliberately, poll its state, and stop it when it is no longer wanted. See [account.md](account.md) before enabling retained signals on a high-volume run.

## Start, inspect, and stop a run

Build `StartLiveRequest` with the live-run name, optional description, visibility, sources, parameter map, and relay choice. `strategyId` identifies the compiled strategy. Sources and parameters use the generated API models so their exact fields stay aligned with the OpenAPI contract. On each `LiveSourceRequest`, `instruments` may be omitted: the platform then uses the instruments declared by the compiled QTScript strategy, or all instruments in the selected exchange/segment if that strategy declares none. An explicit non-empty list still selects the requested instruments; an empty or `null` list is rejected.

```java
StartLiveRequest request = new StartLiveRequest().name("ETH breakout").description("Monitored execution").relay(true);
LiveRunWithStream run = qts.startLive(strategyId, request);
LiveRunWithStream current = qts.getLive(strategyId);
System.out.println(current.getRunId() + " " + current.getState());
qts.stopLive(strategyId);
```

`getLive(strategyId)` and `stopLive(strategyId)` address the one live run of a strategy. `stopLive` requests a stop; inspect the returned or subsequent `LiveRun.state` before assuming execution has ended.

### Warm up indicators before a run starts

Set `warmFrom(seconds)` only on `StartLiveRequest` to replay market history before the first live
event. It accepts 0–3600 seconds; `0` disables warmup. Omit it to preserve the platform's automatic
0–900-second warmup, aligned to the current 15-minute block. The effective value is available from
`LiveRunWithStream.getWarmFrom()` and `LiveRunDetail.getWarmFrom()`; it may be absent only for a run
created before this field existed. It cannot be changed by `updateLiveParams`: stop and start a new
run to use a different warmup.

```java
LiveRunWithStream run = qts.startLive(strategyId, new StartLiveRequest()
        .name("ETH breakout")
        .warmFrom(300));
System.out.println(run.getWarmFrom());
```

### Plain WebSocket stream

Set `stream(true)` when starting a run to receive a secret plain-WebSocket URL in
`LiveRunWithStream.streamUrl`. It emits one JSON signal per text frame from sandbox onward and
also enables retained-signal relay. Treat the URL as a credential: do not log it, include it in
issues, or expose it to untrusted callers. `getLive(strategyId)` returns it while the run is active.

```java
LiveRunWithStream run = qts.startLive(strategyId, new StartLiveRequest()
        .name("ETH breakout")
        .stream(true));
URI streamUrl = run.getStreamUrl();
```

Use `getLiveRun(runId)` to inspect one owned run by its canonical identity, including
`updatedAtMs` and current optional `stats`. `rotateLiveStream(runId)` replaces the stream URL;
`revokeLiveStream(runId)` permanently disables it. A revoked stream cannot be added back to an
existing run.

### Simulate fills with paper trading

Paper trading is opt-in. Add `paper` to the start request to simulate fills, per-quote-currency
balances, open positions, and backtest-style KPIs. It never submits orders to an exchange. Without
the `paper` block, the paper read methods return `404`.

```java
StartLiveRequest request = new StartLiveRequest()
        .name("ETH paper run")
        .relay(true)
        .paper(new LivePaperConfig()
                .initialFunding(1_000.0)
                .feeRate(0.001)
                .feeLeg(LivePaperConfig.FeeLegEnum.RECEIVED)
                .percentAmountToLock(20.0)
                .output(LivePaperConfig.OutputEnum.SEPARATE));
LiveRunWithStream paperRun = qts.startLive(strategyId, request);
LivePaper snapshot = qts.getLiveRunPaper(paperRun.getRunId());
snapshot.getAccounts().forEach(account -> System.out.printf(
        "%s equity=%s openPositions=%d%n",
        account.getCurrency(), account.getEquity(), account.getOpenPositions().size()));
```

`initialFunding` defaults to `100` per quote-currency account; `feeRate` defaults to `0.001` and
sets both buy/sell rates unless overridden; `feeLeg` defaults to `RECEIVED` (`QUOTE` and `BASE` are
also accepted); `percentAmountToLock` defaults to the strategy setting or 10% of remaining free
balance; `output` defaults to `SEPARATE`. Each quote currency gets a separate account. When `output`
is `MIX`, paper events are also written into retained signals. See the [API paper-trading guide](https://qtsurfer.github.io/docs/live_paper.html)
for economics, account semantics, and signal event shapes.

The authenticated session exposes the same methods and retries once on `401`:

```java
LivePaper snapshot = authenticated.getLiveRunPaper(paperRun.getRunId());
```

### Read paper-equity history

`getLiveRunPaperEquity(runId, query)` reads the lifetime equity curve oldest first. Omit `currency`
to interleave all quote accounts; use `sinceMs` to start at a market-time instant. The API defaults
`limit` to 100 and caps it at 1000. A cursor takes precedence over `sinceMs`; use the continuation
helper to preserve currency, size, and other link filters.

```java
LivePaperEquityQuery equityQuery = LivePaperEquityQuery.builder()
        .currency("USDT")
        .sinceMs(startedAtMs)
        .limit(100)
        .build();
LivePaperEquityPage equity = qts.getLiveRunPaperEquity(paperRun.getRunId(), equityQuery);
equity.getPoints().forEach(point -> System.out.println(point.getEventTsMs() + " " + point.getEquity()));
qts.getNextLiveRunPaperEquity(paperRun.getRunId(), equity)
        .ifPresent(next -> next.getPoints().forEach(System.out::println));
```

The same methods are available on `AuthenticatedClient`, where each request participates in
refresh-on-401. Paper routes follow the run's access rule: its owner, or any caller when visibility
is public.

## Visibility and parameters

`updateLive(runId, request)` changes mutable run metadata. Supply only the field to change: `visibility`, `name`, or `description`.

```java
qts.updateLive(run.getRunId(), new UpdateLiveRequest()
        .visibility(UpdateLiveRequest.VisibilityEnum.PUBLIC)
        .description("Visible during the US session"));
```

`updateLiveParams(runId, request)` changes strategy parameters without opening a WebSocket. Use `UpdateLiveParamsRequestBuilder` rather than the generated `UpdateLiveParamsRequest`, whose `params` field is untyped because strategy-property names are arbitrary.

```java
LiveParamsUpdateResult changed = qts.updateLiveParams(
        run.getRunId(), UpdateLiveParamsRequestBuilder.builder()
                .param("emaFast", 12)
                .param("emaSlow", 26)
                .param("stopLoss", 0.015));
System.out.println("Active parameter version: " + changed.getParamsVersion());
```

`param(name, value)` adds or replaces one parameter. Use `params(Map<String, ?>)` when the values already exist in a map. Parameter names must match the strategy's declared properties; the builder preserves each value's Java type for the API.

Use the generated-request overload only as an advanced escape hatch for a generated-client extension.

### Send a transient command

`sendLiveCommand(runId, request)` tells a running strategy something without restarting it. The
strategy must implement the engine's `CommandRequestHandler`, and the caller must own the run. A
command is an event, not a stored parameter: it is delivered to executions behind the run at the
same market position, but replicas started later do not receive it. Use parameter updates for state
that must survive restarts.

Build the request with `LiveCommandRequestBuilder` rather than constructing an untyped `Object`
properties field directly:

```java
LiveCommandResult accepted = qts.sendLiveCommand(run.getRunId(),
        LiveCommandRequestBuilder.builder()
                .command("rebalance")
                .property("targetWeight", 0.25)
                .property("reason", "risk threshold")
                .build());
System.out.println(accepted.getCommandId() + " effective at " + accepted.getEffectiveAtMs());
```

Use `properties(Map<String, ?>)` when values already exist in a map. The `202` response means the
command was accepted, not that the strategy has completed handling it. A `503` means it was not sent
and can be retried. There is no idempotency key, so an ambiguous network failure may have delivered
the command; avoid blind retries. The same methods are available on `AuthenticatedClient` and
participate in its refresh-on-401 flow.

## Discover public runs

`listLive(cursor, limit)` lists every run owned by the account, including sandbox and stopped runs.
`listPublicLive(cursor, limit)` lists only public runs. These are distinct catalogues. `cursor`
continues a previous page and `limit` bounds its size; pass `null` for either API default. Follow the
response next link rather than synthesising cursors.

```java
PublicLiveListResponse firstPage = qts.listPublicLive(null, 25);
firstPage.getRuns().forEach(item -> System.out.println(item.getRunId()));

LiveListResponse ownedRuns = qts.listLive(null, 25);
ownedRuns.getRuns().forEach(item -> System.out.println(item.getRunId() + " " + item.getState()));
```

## Real-time signals and parameter updates

For signals as they happen, use `connectLive(runId, options)` on either `QTSurfer` or
`AuthenticatedClient`. Start the run with `relay(true)`; this is separate from reading its retained
history. The connection mints a short-lived token via the configured REST client, subscribes to
`sig:<runId>`, and completes its future only after the subscription succeeds. An authenticated
session also refreshes its JWT when token minting returns `401`.

```java
import com.qtsurfer.api.client.model.LiveParamsUpdateResult;
import com.qtsurfer.api.client.model.LiveRun;
import com.qtsurfer.api.client.model.StartLiveRequest;
import com.qtsurfer.api.sdk.LiveConnection;
import com.qtsurfer.api.sdk.LiveConnectionOptions;
import com.qtsurfer.api.sdk.QTSurfer;
import com.qtsurfer.api.sdk.UpdateLiveParamsRequestBuilder;
import com.qtsurfer.api.sdk.auth.AuthenticatedClient;

AuthenticatedClient qts = QTSurfer.authenticate();
LiveRun run = qts.startLive(strategyId, new StartLiveRequest()
        .name("ETH breakout")
        .relay(true));

LiveConnectionOptions options = LiveConnectionOptions.builder(signal ->
        System.out.println(signal.getSignalId() + " " + signal.getKind()))
        .onError(error -> System.err.println("Live connection: " + error.getMessage()))
        .build();

try (LiveConnection connection = qts.connectLive(run.getRunId(), options).join()) {
    LiveParamsUpdateResult changed = connection.updateParams(
            UpdateLiveParamsRequestBuilder.builder().param("emaFastPeriod", 12)).join();
    System.out.println("Active parameter version: " + changed.getParamsVersion());
}
```

`updateParams` also accepts `Map<String, ?>` and the generated `UpdateLiveParamsRequest`. It uses
the owner-only `live.params` WebSocket RPC and returns the same typed result as the REST parameter
update. A rejected subscription fails the `connectLive` future; an RPC error fails the
`updateParams` future with `QTSError`. `onError` reports later transport, subscription, and decoding
problems. Close the connection (or call `disconnect()`) to stop reconnecting and release its threads.
Override the default staging endpoint with `options.url("wss://...")` when targeting another
deployment; its REST base URL must mint tokens accepted by that WebSocket server.

The Centrifugo Java client negotiates the `centrifuge-protobuf` WebSocket subprotocol. Signal and
RPC data inside those frames remain JSON and are converted to the generated Java models.

The Centrifugo client reconnects and renews connection tokens automatically. A reconnect can leave
a gap in the stream: use `connection.history()` for recent sandbox signals or `getLiveSignals` for
durable retained signals in either stage. Deduplicate by `signalId` when combining history with the
WebSocket stream. The subscription itself does not promise replay.

### Read recent sandbox signals over WebSocket

`connection.history()` reads up to 300 signals that the subscribed `sig:<runId>` channel still
holds, oldest first. Each publication has its original stream `offset`; the reply also supplies
an `epoch` and the newest channel offset. Subscribe first (as `connectLive` does), then read
history and deduplicate overlapping pushed signals by `signalId`.

```java
LiveSignalHistory first = connection.history().join();
first.publications().forEach(item ->
        System.out.println(item.offset() + " " + item.signal().getSignalId()));
LiveSignalHistory later = connection.history(300, first.position()).join();
```

Keep the position from an earlier history reply, plus the last signal offset you have, to read
only later signals after reconnecting. `history(0, null)` returns the current position without
signals. `first.position()` uses the last returned publication's offset, so pages smaller than 300
do not skip held signals; for an empty reply it uses the newest channel offset. If error `112`
reports a lost epoch, retry without a position; signals older than the
channel now holds require REST `getLiveSignals`. Error `103` means the connection is not subscribed.
Only sandbox signals are held: the channel keeps the newest 300 until five minutes after its last
sandbox signal and never holds live-stage signals. The REST endpoint is the recovery path for
anything outside that short-lived window.

## Retained signal history

`getLiveSignals(runId, query)` returns one oldest-first `LiveSignalPage`. Build filters with
`LiveSignalsQuery`; all fields are optional and omitted values use API defaults.

- `sinceMs` is an epoch-millisecond lower bound; omit it to start at the oldest retained signal.
- `instrument` accepts one instrument, a wildcard, or a comma-separated list.
- `cursor` continues the response next link and takes precedence over `sinceMs`.
- `limit` bounds the number of returned signals.
- `type` filters signal kind, including `paper` when the run was started with `paper.output = MIX`.

```java
LiveSignalsQuery query = LiveSignalsQuery.builder()
        .sinceMs(startedAtMs)
        .instrument("ETH/USDT")
        .type("paper")
        .limit(100)
        .build();
LiveSignalPage page = qts.getLiveSignals(run.getRunId(), query);
page.getSignals().forEach(signal -> System.out.println(signal.getSignalId()));
Long oldestAvailable = page.getAvailableSinceMs();

qts.getNextLiveSignals(run.getRunId(), page)
        .ifPresent(nextPage -> nextPage.getSignals().forEach(System.out::println));
```

Deduplicate by `signalId` when combining this history with real-time delivery. `getNextLiveSignals`
follows the HAL link and retains its `sinceMs`, instrument, type, and limit filters. A cursor can
expire as retention advances; restart without it. The replacement page reports `availableSinceMs`,
the earliest point that remains readable.
