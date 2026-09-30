# API coverage

Measured against API spec **0.128.14**. The SDK wraps 44 of 45 REST operations in task-oriented
methods. `mintLiveConnectionToken` remains available through the generated client; the SDK does not
provide a managed WebSocket connection.

| Domain | SDK surface | Deliberate boundary |
| --- | --- | --- |
| Authentication | `authenticate`; `AuthenticatedClient.refresh()` | Authentication creates `AuthenticatedClient`; a caller-managed JWT uses `QTSurfer.builder()`. |
| Account | `getAccount()`, `getAccountUsage()` | Available on `QTSurfer` and API-key-backed `AuthenticatedClient`; see [account.md](account.md). |
| Exchange | `getExchanges()`, `getInstruments(exchangeId[, segment])`, `downloadTickers(...)`, `downloadKlines(...)` | Lists are unwrapped from HAL envelopes and downloads are streams. |
| Strategy | `compile`, `validateStrategy`, `getStrategyState`, `getStrategies(includeDeleted)`, `deleteStrategy`, `getStrategyCode` | `includeDeleted=true` returns soft-deleted entries with `deletedAt`; `listStrategies` and `strategyState` remain aliases. |
| Backtesting | `executeBacktest(...)`, `getBacktestResult(...)`, `Backtest.cancel()` | `prepare` and `execute` identifiers stay inside the workflow. |
| Sweeps | `sweep(...)`, `Sweep.getResults(...)`, `Sweep.cancel()`, `Sweep.getSensitivity(...)`, `getSweepRunEquityCurve(...)` | `getBoundedSweepRunEquityCurve(...)` is the safe, normalized default. |
| Dataset | `createDataset`, `importDataset`, `getDatasetImport`, `getDatasets(includeDeleted)`, `getDataset`, `deleteDataset`, `openDatasetUpload`, `uploadDatasetFile`, `finalizeDatasetUpload`, `getDatasetUpload` | `includeDeleted=true` returns soft-deleted entries with `deletedAt`; upload bytes go directly to the presigned target. |
| Live execution | `startLive`, `getLive`, `stopLive`, `listLive`, `listPublicLive`, `updateLive`, `updateLiveParams`, `sendLiveCommand`, `getLiveSignals`, `getNextLiveSignals`, `getLiveRunPaper`, `getLiveRunPaperEquity`, `getNextLiveRunPaperEquity` | `LiveCommandRequestBuilder` sends a transient owner-only command to a running strategy; the command is not persisted or replayed. `LiveSignalsQuery` and `LivePaperEquityQuery` name optional filters; continuation helpers retain filters. Paper simulation is opt-in. |

The SDK intentionally does not expose standalone `prepare` or `execute` methods. They are workflow
stages whose temporary ids are not useful application state; preparation is idempotent, so this
encapsulation does not duplicate work. See the linked domain guide before falling back to the
generated client.
