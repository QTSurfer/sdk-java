package com.qtsurfer.api.sdk.errors;

/**
 * Failure while transferring bytes to a presigned dataset upload target.
 *
 * <p>The exception deliberately does not retain a transport cause because transport exception
 * messages can contain the complete presigned URL. Callers can therefore log this exception
 * without disclosing the upload credentials embedded in that URL.
 *
 * @since 0.18.0
 */
public final class QTSUploadError extends QTSError {

    private final Integer statusCode;

    /**
     * Create a failure without an HTTP response status.
     *
     * @param message safe error description that does not contain the presigned URL
     */
    public QTSUploadError(String message) {
        this(message, null);
    }

    /**
     * Create a failure with the status returned by the upload target.
     *
     * @param message safe error description that does not contain the presigned URL
     * @param statusCode HTTP response status, or {@code null} when no response was received
     */
    public QTSUploadError(String message, Integer statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    /**
     * Return the status produced by the upload target.
     *
     * @return HTTP response status, or {@code null} when no response was received
     */
    public Integer statusCode() {
        return statusCode;
    }
}
