package com.qtsurfer.api.sdk.internal;

/**
 * Maps the raw backend job status (which in the live API is lowercase —
 * {@code queued}, {@code started}, {@code completed}, {@code failed}, …)
 * to a stable enum so the rest of the SDK reasons about it without caring
 * about OpenAPI spec drift or generator casing.
 */
public final class StatusNormalizer {

    private StatusNormalizer() {}

    public enum Normalized { IN_PROGRESS, COMPLETED, FAILED, ABORTED }

    public static Normalized normalize(Object raw) {
        if (raw == null) return Normalized.IN_PROGRESS;
        String value = raw.toString().toLowerCase(java.util.Locale.ROOT);
        return switch (value) {
            // A single-instrument prepare is terminal as soon as it returns Completed;
            // the caller reads the reported coverage ratio to decide what to do with a
            // partially-covered window (spec 0.98.0 dropped the old Partial status).
            case "completed" -> Normalized.COMPLETED;
            // A sweep that finishes with at least one shard dead reports `partial`, and that is
            // terminal: the rows it did produce are readable and nothing more is coming. Only the
            // sweep statuses use this value, so mapping it here does not reach the prepare or
            // execute paths. It normalizes to COMPLETED because this enum drives the poll loop —
            // stop asking, hand the response back — and the caller reads `partial` off the
            // response itself, where the distinction is not lost.
            case "partial" -> Normalized.COMPLETED;
            case "failed" -> Normalized.FAILED;
            case "aborted", "cancelled", "canceled" -> Normalized.ABORTED;
            default -> Normalized.IN_PROGRESS;
        };
    }
}
