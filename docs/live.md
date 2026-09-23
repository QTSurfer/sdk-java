# Live execution

Live methods are available on `QTSurfer` (caller-managed JWT) and API-key-authenticated
`AuthenticatedClient` (proactive refresh and retry-on-401). For a long-running process, prefer the
authenticated client; see [auth.md](auth.md) for setup and token ownership.

Live execution runs a compiled strategy continuously. It is separate from a backtest: start it deliberately, poll its state, and stop it when it is no longer wanted. See [account.md](account.md) before enabling retained signals on a high-volume run.

## Start, inspect, and stop a run

Build `StartLiveRequest` with the live-run name, optional description, visibility, sources, parameter map, and relay choice. `strategyId` identifies the compiled strategy. Sources and parameters use the generated API models so their exact fields stay aligned with the OpenAPI contract.

```java
StartLiveRequest request = new StartLiveRequest().name("ETH breakout").description("Monitored execution").relay(true);
LiveRun run = qts.startLive(strategyId, request);
LiveRun current = qts.getLive(strategyId);
System.out.println(current.getRunId() + " " + current.getState());
qts.stopLive(strategyId);
```

`getLive(strategyId)` and `stopLive(strategyId)` address the one live run of a strategy. `stopLive` requests a stop; inspect the returned or subsequent `LiveRun.state` before assuming execution has ended.

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

## Retained signal history

`getLiveSignals(runId, sinceMs, instrument, cursor, limit)` returns one oldest-first `LiveSignalPage`.

- `sinceMs` is an epoch-millisecond lower bound; omit it to start at the oldest retained signal.
- `instrument` accepts one instrument, a wildcard, or a comma-separated list.
- `cursor` continues the response next link and takes precedence over `sinceMs`.
- `limit` bounds the number of returned signals.

```java
LiveSignalPage page = qts.getLiveSignals(run.getRunId(), null, "ETH/USDT", null, 100);
page.getSignals().forEach(signal -> System.out.println(signal.getSignalId()));
Long oldestAvailable = page.getAvailableSinceMs();

qts.getNextLiveSignals(run.getRunId(), page)
        .ifPresent(nextPage -> nextPage.getSignals().forEach(System.out::println));
```

Deduplicate by `signalId` when combining this history with real-time delivery. A cursor can expire as retention advances; restart without it. The replacement page reports `availableSinceMs`, the earliest point that remains readable.
