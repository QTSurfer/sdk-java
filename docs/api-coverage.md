# API coverage

Measured against OpenAPI **0.128.24** and AsyncAPI **0.3.0**. The SDK wraps all 48 REST operations: 47 as task-oriented
methods and `mintLiveConnectionToken` internally through `connectLive`. The latter provides a
managed WebSocket connection to a live run's signal channel.

| Domain | SDK surface | Deliberate boundary |
| --- | --- | --- |
| Authentication | `authenticate`; `AuthenticatedClient.refresh()` | Authentication creates `AuthenticatedClient`; a caller-managed JWT uses `QTSurfer.builder()`. |
| Account | `getAccount()`, `getAccountUsage()` | Available on `QTSurfer` and API-key-backed `AuthenticatedClient`; see [account.md](account.md). |
| Exchange | `getExchanges()`, `getInstruments(exchangeId[, segment])`, `downloadTickers(...)`, `downloadKlines(...)` | Lists are unwrapped from HAL envelopes and downloads are streams. |
| Strategy | `compile`, `validateStrategy`, `getStrategyState`, `getStrategies(includeDeleted)`, `deleteStrategy`, `getStrategyCode` | `includeDeleted=true` returns soft-deleted entries with `deletedAt`; `listStrategies` and `strategyState` remain aliases. |
| Backtesting | `executeBacktest(...)`, `getBacktestResult(...)`, `Backtest.cancel()` | `prepare` and `execute` identifiers stay inside the workflow. |
| Sweeps | `sweep(...)`, `Sweep.getResults(...)`, `Sweep.cancel()`, `Sweep.getSensitivity(...)`, `getSweepRunEquityCurve(...)` | `getBoundedSweepRunEquityCurve(...)` is the safe, normalized default. |
| Dataset | `createDataset`, `importDataset`, `getDatasetImport`, `getDatasets(includeDeleted)`, `getDataset`, `deleteDataset`, `openDatasetUpload`, `uploadDatasetFile`, `finalizeDatasetUpload`, `getDatasetUpload` | `includeDeleted=true` returns soft-deleted entries with `deletedAt`; upload bytes go directly to the presigned target. |
| Live execution | `startLive`, `getLive`, `getLiveRun`, `stopLive`, `listLive`, `listPublicLive`, `updateLive`, `updateLiveParams`, `rotateLiveStream`, `revokeLiveStream`, `sendLiveCommand`, `connectLive`, `LiveConnection.updateParams`, `LiveConnection.history`, `getLiveSignals`, `getNextLiveSignals`, `getLiveRunPaper`, `getLiveRunPaperEquity`, `getNextLiveRunPaperEquity` | `StartLiveRequest.warmFrom` is start-only and surfaced by `LiveRunWithStream`/`LiveRunDetail`. `LiveSourceRequest.instruments` may be omitted to use the compiled strategy selection or all exchange/segment instruments when none is declared; returned sources report the effective selection. `connectLive` manages the WebSocket token, `sig:<runId>` subscription and reconnects. WebSocket history covers only recent sandbox signals; REST retained signals cover gaps outside that window. `streamUrl` is a secret credential, returned only to the owner. `LiveCommandRequestBuilder` sends owner-only transient commands. Paper simulation is opt-in. |

The SDK intentionally does not expose standalone `prepare` or `execute` methods. They are workflow
stages whose temporary ids are not useful application state; preparation is idempotent, so this
encapsulation does not duplicate work. See the linked domain guide before falling back to the
generated client.
