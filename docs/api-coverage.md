# API coverage

Measured against API spec **0.111.2**. All 29 operations are reachable from this SDK. Unqualified
methods exist on both `QTSurfer` and `AuthenticatedClient`, except that authentication creates the
latter.

| Section | Operations and SDK surface |
| --- | --- |
| Auth | `authenticate`; `AuthenticatedClient.refresh()` re-mints a JWT |
| Exchange | `getExchanges()`, `getInstruments(exchangeId[, segment])`, `downloadTickers(...)`, `downloadKlines(...)` |
| Strategy | `compile`, `validateStrategy`, `getStrategyState`, `getStrategies`, `deleteStrategy`, `getStrategyCode` |
| Backtesting | `executeBacktest(...)` workflow handles prepare and execute; `getBacktestResult(...)` reads an existing run; `Backtest.cancel()` cancels one |
| Sweeps | `sweep(...)` workflow; `Sweep.getResults(...)`, `Sweep.cancel()`, `Sweep.getSensitivity(...)`, `getSweepRunEquityCurve(...)` |
| Dataset | `createDataset`, `getDatasets`, `getDataset`, `deleteDataset`, `openDatasetUpload`, `uploadDatasetFile`, `finalizeDatasetUpload`, `getDatasetUpload` |

The SDK intentionally does not expose standalone `prepare` or `execute` methods. They are workflow
stages whose ids remain internal to `executeBacktest(...)` and `sweep(...)`; preparation is idempotent, so
that encapsulation does not duplicate work. See the section guides from the [README](../README.md)
for caller behavior and lifecycle details.
