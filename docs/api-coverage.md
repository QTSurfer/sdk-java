# API coverage

Measured against API spec **0.111.2**. All 29 operations are reachable from this SDK. Unqualified
methods exist on both `QTSurfer` and `AuthenticatedClient`, except that authentication creates the
latter.

| Section | Operations and SDK surface |
| --- | --- |
| Auth | `authenticate`; `AuthenticatedClient.refresh()` re-mints a JWT |
| Exchange | `exchanges()`, `instruments(exchangeId[, segment])`, `tickers(...)`, `klines(...)` |
| Strategy | `compile`, `validateStrategy`, `strategyState`, `listStrategies`, `deleteStrategy`, `getStrategyCode` |
| Backtesting | `backtest(...)` workflow handles prepare and execute; `backtestResult(...)` reads an existing run; `Backtest.cancel()` cancels one |
| Sweeps | `sweep(...)` workflow; `Sweep.results(...)`, `Sweep.cancel()`, `Sweep.sensitivity(...)`, `getSweepRunEquityCurve(...)` |
| Dataset | `createDataset`, `listDatasets`, `dataset`, `deleteDataset`, `openDatasetUpload`, `uploadDatasetFile`, `finalizeDatasetUpload`, `datasetUpload` |

The SDK intentionally does not expose standalone `prepare` or `execute` methods. They are workflow
stages whose ids remain internal to `backtest(...)` and `sweep(...)`; preparation is idempotent, so
that encapsulation does not duplicate work. See the section guides from the [README](../README.md)
for caller behavior and lifecycle details.
