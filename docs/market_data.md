# Market data

This SDK guide mirrors the API's [market-data guide](https://qtsurfer.github.io/docs/market_data.html).
The SDK returns domain values for catalogue reads and streams downloads without buffering them.

## Discover exchanges and instruments

`getExchanges()` lists markets available to the platform. `getInstruments(exchangeId)` reads the
default `spot` segment; pass `spot` or `futures` explicitly when the segment matters.

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

var futures = qts.getInstruments("binancefutures", "futures");
```

The SDK unwraps the API's HAL envelope into `List<InstrumentDetail>`. Coverage is live platform
state and is not cached. A missing exchange or segment raises `QTSError`.

## Download an hour of market data

`downloadTickers` and `downloadKlines` stream exactly one UTC hour of data. `hour` is
`YYYY-MM-DDTHH`, for example `2026-01-15T10`. Lastra is the default; request
`DownloadFormat.PARQUET` for on-demand conversion. Always close the returned stream.

```java
import com.qtsurfer.api.sdk.DownloadFormat;

try (var input = qts.downloadTickers("binance", "BTC", "USDT", "2026-01-15T10")) {
    Files.copy(input, Path.of("BTC_USDT_2026-01-15_h10.lastra"));
}

try (var input = qts.downloadKlines(
        "binance", "BTC", "USDT", "2026-01-15T10", DownloadFormat.PARQUET)) {
    // Consume or save the Parquet stream.
}
```

Downloads are synchronous and stream directly from the HTTP response; do not accumulate a segment
in memory. HTTP or transport failures raise `QTSDownloadError`, a `QTSError` subtype. The same
methods are available on `QTSurfer` and on `AuthenticatedClient`; the latter refreshes its JWT once
on a `401` before retrying the request.
