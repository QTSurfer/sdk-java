# Market data

Examples assume an authenticated `QTSurfer qts`; see [auth.md](auth.md) for setup and token ownership.

This SDK guide mirrors the API's [market-data guide](https://qtsurfer.github.io/docs/market_data.html).
The SDK returns domain values for catalogue reads and streams downloads without buffering them.

## Discover exchanges and instruments

`getExchanges()` lists CEX and DEX venues with `venueType`, `segments` and `liveSegments`.
Use these values together: catalogue segments can include futures without live data.
`getInstruments(exchangeId)` reads a CEX venue's default `spot` segment; pass a segment explicitly
when it matters. A DEX venue uses `getPools` instead.

```java
import com.qtsurfer.api.client.model.CoverageWindow;
import com.qtsurfer.api.client.model.Exchange;
import com.qtsurfer.api.client.model.InstrumentDetail;

for (Exchange exchange : qts.getExchanges()) {
    System.out.println(exchange.getId());
}

for (InstrumentDetail instrument : qts.getInstruments("binance")) {
    CoverageWindow coverage = instrument.getCoverage().getTickers();
    System.out.printf("%s: %s to %s%n", instrument.getId(), coverage.getFrom(), coverage.getTo());
}

var futures = qts.getInstruments("binance", "futures");
```

The SDK unwraps the API's HAL envelope into `List<InstrumentDetail>`. Coverage is live platform
state and is not cached. `symbol` equals `id`; `settle` identifies settlement currency when present.
Absent `lastPrice` or `volume24h` means unknown, not zero. Invalid exchange/segment combinations
return `400`; an unavailable catalogue returns `404`. A DEX response raises `QTSError` with advice
to use `getPools`, rather than losing its pool fields.

## Discover DEX pools

```java
import com.qtsurfer.api.client.model.PoolDetail;

for (PoolDetail pool : qts.getPools("robinhood", "uniswap-v3")) {
    System.out.printf("%s %s liquidity=%s%n", pool.getId(), pool.getSymbol(), pool.getTvlUsd());
}
```

`getPools(exchangeId)` selects the venue's first protocol; its two-argument overload accepts any
protocol listed in `Exchange.segments`. Both return `List<PoolDetail>` on `QTSurfer` and
`AuthenticatedClient`. Pool `id` is the address to start live execution with; `symbol` is the pair
used by signals. `base`/`quote` and their addresses identify tokens, `feePercent` is the pool fee,
and `tvlUsd` and `volume24hUsd` are USD values. Optional `lastPrice` is base in quote tokens;
optional `volume24h` is quote-token volume; absent `createdAt` means no known creation date.
The catalogue contains the 50 pools with most 24-hour volume among those with at least USD 10,000
liquidity, refreshed hourly (including price/volume). Valid unlisted pools can still run.
Use `getInstruments` for CEX; passing one to `getPools` is an explicit error.

## Download an hour of market data

`downloadTickers` and `downloadKlines` stream one UTC-hour segment from the last seven days. `hour` is
`YYYY-MM-DDTHH`; generate a recent UTC value rather than copying a historical date. Lastra is the default; request
`DownloadFormat.PARQUET` for on-demand conversion. Always close the returned stream.

```java
import com.qtsurfer.api.sdk.DownloadFormat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

String hour = ZonedDateTime.now(ZoneOffset.UTC).minusHours(2)
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH"));
try (var input = qts.downloadTickers("binance", "BTC", "USDT", hour)) {
    Files.copy(input, Path.of("BTC_USDT.lastra"));
}

try (var response = qts.downloadKlinesWithMetadata(
        "binance", "BTC", "USDT", hour, DownloadFormat.LASTRA)) {
    System.out.println(response.timestampUnit());
    System.out.println(response.dataStatus());
    System.out.println(response.dataThrough());
    Files.copy(response.body(), Path.of("BTC_USDT_klines.lastra"));
}
```

Downloads are synchronous and stream directly from the HTTP response; do not accumulate a segment
in memory. HTTP or transport failures raise `QTSDownloadError`, a `QTSError` subtype. The same
methods are available on `QTSurfer` and on `AuthenticatedClient`; the latter refreshes its JWT once
on a `401` before retrying the request.

`downloadTickersWithMetadata` and `downloadKlinesWithMetadata` preserve `Content-Type`,
`X-Ts-Unit`, `X-Data-Status`, `X-Data-Through` and other headers on a closeable `BinaryDownload`.
Pass `null` format for Lastra. Lastra is `application/vnd.qtsurfer.lastra`; its `ts` is epoch
microseconds, declared in column metadata `ts_unit: micros` and `X-Ts-Unit: micros`. Parquet's
`TIMESTAMP` remains milliseconds. Bytes are returned untouched. The old stream methods retain
their signatures; use metadata methods when timestamp interpretation or caching needs headers.

Recent and in-progress hours may be `200` with `X-Data-Status: provisional` and `X-Data-Through`
(last row's ISO-8601 UTC time regardless of timestamp units). Such an hour can grow and be replaced
by stored data. A deployment can instead answer `404 NOT_YET_AVAILABLE` with `Retry-After`, also
used when there are no provisional rows to serve. `404 NO_DATA` means a stored hour has no rows for
the instrument; `503 TEMPORARILY_UNAVAILABLE` requests a retry after `Retry-After`.

An hour that started more than seven days ago returns `400 OUTSIDE_WINDOW`. Inspect
`QTSDownloadError.getReason()`, `getNext()` and `getResponseHeaders()`: `next` contains a `POST
/datasets/imports` CEX import of that exact hour. The SDK preserves that request and never sends it
automatically. See [datasets.md](datasets.md) for older history and range imports. Unknown reasons
should be treated like an absent reason, with the HTTP status and message available for diagnosis.
