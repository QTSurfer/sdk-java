# Dataset uploads and imports

Examples assume an authenticated `QTSurfer qts`; see [auth.md](auth.md) for setup and token ownership.

Use a dataset when the backtest or sweep should run against your own ticker CSV or Parquet rather than a
managed exchange. The SDK performs the authenticated API calls and streams the file directly to a
presigned storage target; the direct transfer intentionally carries no API credentials.

## List datasets

`getDatasets()` returns active datasets newest first. Pass `true` to include soft-deleted entries
and their `deletedAt` timestamps when synchronizing a local catalogue; the default is `false`.

```java
var all = qts.getDatasets(true);
all.stream().filter(dataset -> dataset.getDeletedAt() != null)
        .forEach(dataset -> System.out.println(dataset.getDatasetId() + " deleted " + dataset.getDeletedAt()));
```

## Import external history

For supported external sources, ask the platform to fetch and ingest the history instead of uploading
a file. Poll the import until it is `READY` or `FAILED`; a ready import includes the created dataset
and version identifiers.

```java
import com.qtsurfer.api.client.model.DatasetImportDexRequest;
import com.qtsurfer.api.client.model.DatasetImportRequest;

import java.time.OffsetDateTime;

var created = qts.importDataset(new DatasetImportRequest()
        .name("weth-usdc-week")
        .instrument("WETH/USDC")
        .from(OffsetDateTime.parse("2026-08-01T00:00:00Z"))
        .to(OffsetDateTime.parse("2026-08-08T00:00:00Z"))
        .type(DatasetImportRequest.TypeEnum.DEX)
        .dex(new DatasetImportDexRequest()
                .network(DatasetImportDexRequest.NetworkEnum.ETHEREUM)
                .id(DatasetImportDexRequest.IdEnum.UNISWAP)
                .version(DatasetImportDexRequest.VersionEnum.V3)
                .contract("0x88e6a0c2ddd26feeb64f039a2c41296fcb3f5640")));

var state = qts.getDatasetImport(created.getDatasetId(), created.getImportId());
```

The generated `DatasetImportRequest` model exposes the source-specific request fields. Keep polling
with `getDatasetImport` while its status is `FETCHING` or `INGESTING`, then inspect its error message
when it is `FAILED`.

For CEX history, including hours older than the seven-day direct-download window, select `CEX`
and provide the `cex` block. `exchange` must be a CEX id from `getExchanges()`; `spot` is the only
import segment today. `dataType` selects `ticker` or `klines`, and becomes the type for a CEX
import. For uploads, the version's type is inferred from its rows when ingest reaches `READY`:
order-book rows are `ticker`, complete OHLC rows without an order book are `klines`, and ambiguous
data defaults to `ticker`. Kline backtests over `exchangeId: user` require a ready dataset version
whose type is `klines`; prepare rejects other types with `400` before queuing a run.

```java
import com.qtsurfer.api.client.model.DatasetImportCexRequest;
import com.qtsurfer.api.client.model.DatasetImportRequest;
import java.time.OffsetDateTime;

var imported = qts.importDataset(new DatasetImportRequest()
        .name("btc-history").instrument("BTC/USDT")
        .from(OffsetDateTime.parse("2026-10-01T00:00:00Z"))
        .to(OffsetDateTime.parse("2026-10-02T00:00:00Z"))
        .type(DatasetImportRequest.TypeEnum.CEX)
        .cex(new DatasetImportCexRequest().exchange("binance")
                .segment(DatasetImportCexRequest.SegmentEnum.SPOT)
                .dataType(DatasetImportCexRequest.DataTypeEnum.KLINES)));
var importedState = qts.getDatasetImport(imported.getDatasetId(), imported.getImportId());
```

CEX and DEX candle imports use the cadences included in the account's plan. Omit `cadence` to use the
plan default. The accepted `DatasetImportCreated` response includes `getCadence()` when the effective
cadence is already known; a plan without the source's native rate may omit it for a CEX import whose
range determines the common cadence. Asking for a cadence outside the plan returns a non-retryable
`402 PlanRequiredError`; inspect `QTSError.getUpgradeUrl()` when provided. DEX candle imports accept
any plan cadence, and `dex.id` / `dex.version` are unnecessary for this shape.

A DEX import with no cadence uses the plan default to build candles (`type=klines`). On a plan that
includes the source's native rate, omitting cadence can instead request per-trade history; provide
`id` and `version` for that form. Dataset versions report their discovered cadence, including `rt`
for irregular per-trade data. Uploads and prepares against `exchangeId=user` are not limited by the
plan's exchange cadences. Imports consume dataset limits and are bounded by account
`maxImportRangeHours`.

Solana DEX imports support candles only. Even Elite uses its plan default (`1s`) when `cadence` is
omitted; `id`, `version`, and `factory` are never needed. The address is base58 and case-sensitive.
Candle imports whose `from` is older than 180 days return `400` immediately on every network;
per-trade and CEX imports are not subject to that history limit. DEX candle grids can be sparse: a
bar may be absent for an interval with no trades.

```java
var solanaImport = qts.importDataset(new DatasetImportRequest()
        .name("sol-usdc-history").instrument("SOL/USDC")
        .from(OffsetDateTime.parse("2026-10-27T00:00:00Z"))
        .to(OffsetDateTime.parse("2026-10-27T06:00:00Z"))
        .type(DatasetImportRequest.TypeEnum.DEX)
        .cadence(DatasetImportRequest.CadenceEnum._5M)
        .dex(new DatasetImportDexRequest()
                .network(DatasetImportDexRequest.NetworkEnum.SOLANA)
                .contract("Czfq3xZZDmsdGdUyrNLtRhGc47cXcZtLG4crryfu44zE")));
```

## Upload a first version

Create the dataset and use the returned `DatasetCreated` value as the first upload session. It is
initial metadata plus an upload target, not the full `Dataset` resource.

```java
import com.qtsurfer.api.client.model.CreateDatasetRequest;
import com.qtsurfer.api.client.model.DatasetCreated;

import java.nio.file.Path;

DatasetCreated created = qts.createDataset(new CreateDatasetRequest()
        .name("My BTC ticks")
        .instrument("BTC/USDT"));

qts.uploadDatasetFile(created, Path.of("my-btc-ticks.csv"));
qts.finalizeDatasetUpload(created.getDatasetId(), created.getUploadId());
```

`uploadDatasetFile` streams the file to the target and does not attach the SDK's bearer token or
API key. It accepts a readable regular file only. A failed transfer raises `QTSUploadError`; an
HTTP rejection is available through `statusCode()`. The error never retains the presigned URL,
whose query parameters are credentials.

CSV uploads must have a header and `timestamp` and `close` columns. Parquet uploads are accepted
as-is. Timestamp format and cadence are discovered at ingest. A ready dataset's `dataUrl` and
`dataFormat` identify the stored downloadable representation; inspect `dataFormat` rather than
assuming it matches the uploaded file. See the API's [dataset format reference](https://www.qtsurfer.com/docs/developers/api/datasets)
for the optional columns and validation rules.

## Wait for ingest

Finalization starts asynchronous ingest. Poll until the state is `READY` or `FAILED`; only
`READY` carries a usable version.

```java
import com.qtsurfer.api.client.model.DatasetUploadState;

DatasetUploadState state;
do {
    Thread.sleep(1_000);
    state = qts.getDatasetUpload(created.getDatasetId(), created.getUploadId());
} while (state.getStatus() == DatasetUploadState.StatusEnum.INGESTING
        || state.getStatus() == DatasetUploadState.StatusEnum.UPLOADING);

if (state.getStatus() == DatasetUploadState.StatusEnum.FAILED) {
    throw new IllegalStateException("Dataset ingest failed");
}
```

Keep the poll interval and deadline appropriate for the file size. The returned version contains
the discovered range, cadence, row count, and gap details.

## Upload another version or recover a session

If the first creation response was lost, or a prior version has finished, obtain a session for the
dataset and pass it to the same helper:

```java
var session = qts.openDatasetUpload(datasetId);
qts.uploadDatasetFile(session, Path.of("corrected-btc-ticks.csv"));
qts.finalizeDatasetUpload(datasetId, session.getUploadId());
```

Opening a session is safe to retry while it is still pending: the platform returns the same open
session. Once it has produced a version, that `uploadId` is spent. Re-finalizing it returns `409`;
open a new session rather than reusing its presigned target.

## Run against a ready dataset

After ingest is ready, use `exchangeId("user")` and `datasetId` instead of an exchange instrument.
`datasetVersionId` is optional when a run must stay pinned to a particular historical version.

```java
BacktestRequest request = BacktestRequest.builder()
        .strategy(source)
        .exchangeId("user")
        .datasetId(datasetId)
        .from("2026-04-13T00:00:00Z")
        .to("2026-04-14T00:00:00Z")
        .build();

ResultMap result = qts.executeBacktest(request).join();
```

The same dataset fields are available on `SweepRequest`. The normal backtest and sweep workflows
prepare the requested window before they execute.
