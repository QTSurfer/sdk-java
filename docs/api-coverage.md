# API coverage

Measured against API spec **0.126.2**. The SDK wraps 40 of the 41 REST operations in
task-oriented methods; it intentionally keeps the connection-token operation internal because the
Java SDK does not yet offer a managed WebSocket client. Use the generated API client only when that
low-level operation is explicitly required.

| Domain | SDK surface | Deliberate boundary |
| --- | --- | --- |
| Authentication | `authenticate`; `AuthenticatedClient.refresh()` | Authentication creates `AuthenticatedClient`; a caller-managed JWT uses `QTSurfer.builder()`. |
| Account | `getAccount()`, `getAccountUsage()` | Available on `QTSurfer` and API-key-backed `AuthenticatedClient`; see [account.md](account.md). |
| Exchange | `getExchanges()`, `getInstruments(exchangeId[, segment])`, `downloadTickers(...)`, `downloadKlines(...)` | Lists are unwrapped from HAL envelopes and downloads are streams. |
| Strategy | `compile`, `validateStrategy`, `getStrategyState`, `getStrategies`, `deleteStrategy`, `getStrategyCode` | `listStrategies` and `strategyState` remain aliases. |
| Backtesting | `executeBacktest(...)`, `getBacktestResult(...)`, `Backtest.cancel()` | `prepare` and `execute` identifiers stay inside the workflow. |
| Sweeps | `sweep(...)`, `Sweep.getResults(...)`, `Sweep.cancel()`, `Sweep.getSensitivity(...)`, `getSweepRunEquityCurve(...)` | `getBoundedSweepRunEquityCurve(...)` is the safe, normalized default. |
| Dataset | `createDataset`, `importDataset`, `getDatasetImport`, `getDatasets`, `getDataset`, `deleteDataset`, `openDatasetUpload`, `uploadDatasetFile`, `finalizeDatasetUpload`, `getDatasetUpload` | Upload bytes go directly to the presigned target. |
| Live execution | `startLive`, `getLive`, `stopLive`, `listLive`, `listPublicLive`, `updateLive`, `updateLiveParams`, `getLiveSignals`, `getNextLiveSignals` | Available on both client surfaces. The generated `mintLiveConnectionToken` is deliberately not exposed until Java has a managed WebSocket abstraction. |

The SDK intentionally does not expose standalone `prepare` or `execute` methods. They are workflow
stages whose temporary ids are not useful application state; preparation is idempotent, so this
encapsulation does not duplicate work. See the linked domain guide before falling back to the
generated client.
