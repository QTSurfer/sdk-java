# API coverage

OpenAPI **0.128.49** and AsyncAPI **0.3.0**. This table maps every REST operation to its generated
client and SDK entry point. Workflow stages have no standalone high-level method: the SDK owns
their temporary identifiers, polling and cancellation. The generated client remains available
when an application needs those lower-level calls directly.

## REST operations

| Operation ID | Generated client | SDK entry point or deliberate boundary | Guide |
| --- | --- | --- | --- |
| `authenticate` | `AuthApi.authenticate` | QTSurfer.authenticate; AuthenticatedClient.refresh | [auth](auth.md) |
| `getAccount` | `AccountApi.getAccount` | getAccount; includes scopes and import-range limits | [account](account.md) |
| `getAccountUsage` | `AccountApi.getAccountUsage` | getAccountUsage | [account](account.md) |
| `listExchanges` | `ExchangeApi.listExchanges` | getExchanges; venueType, segments and liveSegments | [market_data](market_data.md) |
| `listInstruments` | `ExchangeApi.listInstruments` | getInstruments(exchangeId) for CEX; getPools(exchangeId) for DEX | [market_data](market_data.md) |
| `listSegmentInstruments` | `ExchangeApi.listSegmentInstruments` | getInstruments(exchangeId, segment); getPools(exchangeId, protocol) | [market_data](market_data.md) |
| `downloadTickers` | `ExchangeApi.downloadTickers` | downloadTickers; downloadTickersWithMetadata uses the binary-safe streaming adapter | [market_data](market_data.md) |
| `downloadKlines` | `ExchangeApi.downloadKlines` | downloadKlines; downloadKlinesWithMetadata uses the binary-safe streaming adapter | [market_data](market_data.md) |
| `listStrategies` | `StrategyApi.listStrategies` | getStrategies(includeDeleted); default active entries only | [strategy](strategy.md) |
| `compileStrategy` | `StrategyApi.compileStrategy` | compile creates a reusable Strategy; Java source is sent as text | [strategy](strategy.md) |
| `validateStrategy` | `StrategyApi.validateStrategy` | validateStrategy; pending validation is a typed outcome | [strategy](strategy.md) |
| `getStrategy` | `StrategyApi.getStrategy` | getStrategyState | [strategy](strategy.md) |
| `deleteStrategy` | `StrategyApi.deleteStrategy` | deleteStrategy | [strategy](strategy.md) |
| `getStrategyCode` | `StrategyApi.getStrategyCode` | getStrategyCode | [strategy](strategy.md) |
| `prepareBacktest` | `BacktestingApi.prepareBacktest` | Internal stage of executeBacktest, Strategy.backtest and sweep; high-level calls use the plan default; generated `PrepareAccepted` reports the effective cadence | [backtesting](backtesting.md) |
| `getPrepareStatus` | `BacktestingApi.getPrepareStatus` | Internal preparation polling; workflows interpret coverage before execution | [backtesting](backtesting.md) |
| `executeSweep` | `BacktestingApi.executeSweep` | sweep returns a Sweep handle | [backtesting](backtesting.md) |
| `getSweepResult` | `BacktestingApi.getSweepResult` | Sweep.getResults; Sweep.await | [backtesting](backtesting.md) |
| `cancelSweep` | `BacktestingApi.cancelSweep` | Sweep.cancel; await waits for pending shards to settle | [backtesting](backtesting.md) |
| `getSweepSensitivity` | `BacktestingApi.getSweepSensitivity` | Sweep.getSensitivity | [backtesting](backtesting.md) |
| `getSweepRunEquityCurve` | `BacktestingApi.getSweepRunEquityCurve` | getSweepRunEquityCurve; getBoundedSweepRunEquityCurve provides normalized bounded points | [backtesting](backtesting.md) |
| `executeBacktest` | `BacktestingApi.executeBacktest` | executeBacktest; Strategy.backtest manages compile, preparation and execution | [backtesting](backtesting.md) |
| `cancelBacktest` | `BacktestingApi.cancelBacktest` | Backtest.cancel on the handle from Strategy.backtest | [backtesting](backtesting.md) |
| `getBacktestResult` | `BacktestingApi.getBacktestResult` | getBacktestResult reads an owned job; Backtest.await polls a submitted job | [backtesting](backtesting.md) |
| `createDataset` | `DatasetApi.createDataset` | createDataset returns initial metadata and its first upload session | [datasets](datasets.md) |
| `listDatasets` | `DatasetApi.listDatasets` | getDatasets(includeDeleted); default active entries only | [datasets](datasets.md) |
| `getDataset` | `DatasetApi.getDataset` | getDataset | [datasets](datasets.md) |
| `deleteDataset` | `DatasetApi.deleteDataset` | deleteDataset | [datasets](datasets.md) |
| `openDatasetUpload` | `DatasetApi.openDatasetUpload` | openDatasetUpload; uploadDatasetFile streams bytes to storage without API credentials | [datasets](datasets.md) |
| `finalizeDatasetUpload` | `DatasetApi.finalizeDatasetUpload` | finalizeDatasetUpload starts asynchronous ingest after transfer | [datasets](datasets.md) |
| `getDatasetUpload` | `DatasetApi.getDatasetUpload` | getDatasetUpload | [datasets](datasets.md) |
| `importDataset` | `DatasetApi.importDataset` | importDataset accepts typed CEX/DEX blocks and optional cadence; `DatasetImportCreated.getCadence()` reports the effective cadence when known | [datasets](datasets.md) |
| `getDatasetImport` | `DatasetApi.getDatasetImport` | getDatasetImport; poll fetching/ingesting until ready or failed | [datasets](datasets.md) |
| `startLive` | `LiveExecutionApi.startLive` | startLive; CEX feeds or one DEX pool, with start-only warmup | [live](live.md) |
| `getLive` | `LiveExecutionApi.getLive` | getLive(strategyId) reads the strategy's latest run | [live](live.md) |
| `stopLive` | `LiveExecutionApi.stopLive` | stopLive(strategyId) requests a stop; inspect actual state afterward | [live](live.md) |
| `listLive` | `LiveExecutionApi.listLive` | listLive(cursor, limit) lists owned runs | [live](live.md) |
| `listPublicLive` | `LiveExecutionApi.listPublicLive` | listPublicLive(cursor, limit) browses public running entries | [live](live.md) |
| `getLiveRun` | `LiveExecutionApi.getLiveRun` | getLiveRun(runId) reads an owned run by canonical id | [live](live.md) |
| `updateLive` | `LiveExecutionApi.updateLive` | updateLive changes visibility and metadata | [live](live.md) |
| `updateLiveParams` | `LiveExecutionApi.updateLiveParams` | updateLiveParams; fluent UpdateLiveParamsRequestBuilder or generated request | [live](live.md) |
| `sendLiveCommand` | `LiveExecutionApi.sendLiveCommand` | sendLiveCommand; fluent LiveCommandRequestBuilder or generated request | [live](live.md) |
| `rotateLiveStream` | `LiveExecutionApi.rotateLiveStream` | rotateLiveStream replaces the secret stream URL | [live](live.md) |
| `revokeLiveStream` | `LiveExecutionApi.revokeLiveStream` | revokeLiveStream permanently disables the URL for that run | [live](live.md) |
| `getLiveRunSignals` | `LiveExecutionApi.getLiveRunSignals` | getLiveSignals; getNextLiveSignals preserves pagination filters | [live](live.md) |
| `getLiveRunPaper` | `LiveExecutionApi.getLiveRunPaper` | getLiveRunPaper; paper simulation must have been enabled at start | [live](live.md) |
| `getLiveRunPaperEquity` | `LiveExecutionApi.getLiveRunPaperEquity` | getLiveRunPaperEquity; getNextLiveRunPaperEquity follows continuation | [live](live.md) |
| `mintLiveConnectionToken` | `LiveExecutionApi.mintLiveConnectionToken` | Internal to connectLive, including renewal; no standalone token-management API | [live](live.md) |

Both `QTSurfer` and API-key-backed `AuthenticatedClient` expose catalogue, dataset, account and live
methods. `AuthenticatedClient` also handles JWT renewal. Binary downloads use the manual
`ExchangeBinaryDownloads.getTickersHour` / `getKlinesHour` streaming adapter because the generated
native HTTP methods do not preserve binary response bodies. Metadata overloads preserve units and
provisional-data boundaries.

## WebSocket operations

The REST client has no AsyncAPI generator. `LiveConnection` uses the official Centrifugo client
for transport and protocol management; see the [live guide](live.md) for replay and recovery.

| AsyncAPI operation | SDK support or deliberate boundary |
| --- | --- |
| `connect` | `connectLive(runId, options)` obtains a REST token and establishes the connection |
| `subscribe` | The connection subscribes to `sig:<runId>` and resubscribes after reconnecting |
| `history` | `LiveConnection.history` returns recent sandbox signals and an epoch-aware position |
| `liveParams` | `LiveConnection.updateParams` sends the parameter update command |
| `refresh` | The connection renews its token through the managed token supplier |
| `receiveSignal` | Typed signal callbacks supplied through `LiveConnectionOptions` |
| `receiveUnsubscribe` | Subscription rejection reaches `LiveConnectionOptions.onError`; a server-ended subscription is not automatically resumed |
| `ping` | The Centrifugo transport handles ping/pong automatically |

Subscribing never replays signals automatically. WebSocket history retains at most 300 recent
sandbox signals until five minutes after the last signal; recover older or live-stage retained signals with REST
`getLiveSignals`. The MCP server deliberately uses REST request/response tools rather than opening
a managed WebSocket: `get_live_signals` provides its retained-history path. New SDK pool discovery
maps to MCP `list_pools`; metadata downloads map to `download_tickers` and `download_klines`.
