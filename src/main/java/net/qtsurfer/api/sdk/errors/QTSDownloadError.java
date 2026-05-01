package net.qtsurfer.api.sdk.errors;

/**
 * Raised when an hourly tickers/klines download fails — wraps the underlying
 * {@link net.qtsurfer.api.client.invoker.ApiException} (HTTP 4xx/5xx, transport error).
 */
public class QTSDownloadError extends QTSError {

    public QTSDownloadError(String message) {
        super(message);
    }

    public QTSDownloadError(String message, Throwable cause) {
        super(message, cause);
    }
}
