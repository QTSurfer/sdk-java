package net.qtsurfer.api.sdk.internal;

import net.qtsurfer.api.sdk.internal.StatusNormalizer.Normalized;

/**
 * Snapshot of a strategy compilation status, normalized to the SDK's internal
 * vocabulary so callers don't depend on the wire casing the backend uses today.
 *
 * @param status        normalized lifecycle state
 * @param strategyId    final {@code strategyId} — non-null when {@link Normalized#COMPLETED}
 * @param statusDetail  diagnostic detail (e.g. compiler error) when {@link Normalized#FAILED}
 */
public record CompileStatus(Normalized status, String strategyId, String statusDetail) {
}
