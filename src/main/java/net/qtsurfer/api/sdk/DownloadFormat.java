package net.qtsurfer.api.sdk;

import net.qtsurfer.api.client.binary.ExchangeBinaryDownloads;

/**
 * Wire format for hourly tickers/klines downloads.
 *
 * <ul>
 *   <li>{@link #LASTRA} — native QTSurfer columnar format ({@code application/vnd.lastra}).
 *       Cheaper to serve and to consume. Use a Lastra reader (e.g. {@code lastra-java}).</li>
 *   <li>{@link #PARQUET} — Apache Parquet, converted on-the-fly server-side
 *       ({@code application/vnd.apache.parquet}). Use when the client can't read Lastra.</li>
 * </ul>
 */
public enum DownloadFormat {
    LASTRA(ExchangeBinaryDownloads.Format.LASTRA),
    PARQUET(ExchangeBinaryDownloads.Format.PARQUET);

    private final ExchangeBinaryDownloads.Format wire;

    DownloadFormat(ExchangeBinaryDownloads.Format wire) {
        this.wire = wire;
    }

    ExchangeBinaryDownloads.Format wire() {
        return wire;
    }
}
