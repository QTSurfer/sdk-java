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
            // A prepare job is terminal as soon as it returns: {@code Completed}, or the
            // legacy {@code Partial} (some hours in the range legitimately hold no data —
            // e.g. low-activity hours — so those hours never arrive). Both mean "ready to
            // execute on the available coverage"; polling on Partial would hang forever.
            case "completed", "partial" -> Normalized.COMPLETED;
            case "failed" -> Normalized.FAILED;
            case "aborted", "cancelled", "canceled" -> Normalized.ABORTED;
            default -> Normalized.IN_PROGRESS;
        };
    }
}
