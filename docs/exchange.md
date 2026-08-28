# Exchanges, instruments, and downloads

## Discover exchanges and instruments

`exchanges()` lists the markets currently available from the platform. `instruments(exchangeId)`
lists the default `spot` segment; use the overload with a segment for a different market segment.

```java
import com.qtsurfer.api.client.model.CoverageWindow;
import com.qtsurfer.api.client.model.Exchange;
import com.qtsurfer.api.client.model.InstrumentDetail;

for (Exchange exchange : qts.exchanges()) {
    System.out.println(exchange.getId());
}

for (InstrumentDetail instrument : qts.instruments("binance")) {
    CoverageWindow coverage = instrument.getCoverage().getTickers();
    System.out.printf("%s: %s to %s%n", instrument.getId(), coverage.getFrom(), coverage.getTo());
}

var futures = qts.instruments("binancefutures", "futures");
```

The SDK unwraps the API's HAL envelope and returns `List<InstrumentDetail>`. Coverage is live
platform state and the SDK does not cache it. A missing exchange or segment is reported as
`QTSError`.

## Download an hour of market data

`tickers` and `klines` stream raw data for one instrument-hour. The caller must close the returned
stream. Lastra is the default format; use `DownloadFormat.PARQUET` for on-demand conversion.

```java
import com.qtsurfer.api.sdk.DownloadFormat;

try (var input = qts.tickers("binance", "BTC", "USDT", "2026-01-15T10")) {
    Files.copy(input, Path.of("BTC_USDT_2026-01-15_h10.lastra"));
}

try (var input = qts.klines(
        "binance", "BTC", "USDT", "2026-01-15T10", DownloadFormat.PARQUET)) {
    // Consume or save the Parquet stream.
}
```

HTTP failures while opening or reading a download surface as `QTSDownloadError`, a subtype of
`QTSError`.
