package com.qtsurfer.api.sdk;

import com.qtsurfer.api.client.model.EquityCurveResult;
import com.qtsurfer.api.sdk.errors.QTSError;

import java.util.ArrayList;
import java.util.List;

/** Normalized bounded sweep-run curve independent of the generated response model. */
public record BoundedEquityCurve(List<EquityCurvePoint> points, int inputPointCount, boolean resampled) {

    /** Default maximum points requested by the high-level SDK method. */
    public static final int DEFAULT_MAX_RESAMPLE = 1_000;
    /** Absolute process-safety ceiling, sized for an authorised Elite/Enterprise request. */
    public static final int MAX_MAX_RESAMPLE = 10_000;

    /**
     * Decode a compact response into absolute points and verify the requested bound.
     *
     * @param result generated result returned by the API
     * @param maxResample requested point ceiling
     * @return immutable normalized curve
     */
    public static BoundedEquityCurve decode(EquityCurveResult result, int maxResample) {
        validateMaxResample(maxResample);
        if (result == null || result.getTimestamps() == null || result.getEquities() == null) {
            throw new QTSError("Sweep curve response did not contain compact point arrays");
        }
        if (result.getTimestamps().size() != result.getEquities().size()) {
            throw new QTSError("Sweep curve response has mismatched timestamp and equity arrays");
        }
        if (result.getTimestamps().size() > maxResample) {
            throw new QTSError("Sweep curve response exceeded requested maxResample=" + maxResample);
        }
        boolean differential = result.getMeta() != null && Boolean.TRUE.equals(result.getMeta().getDifferential());
        List<EquityCurvePoint> points = new ArrayList<>(result.getTimestamps().size());
        long timestamp = 0;
        double equity = 0;
        for (int index = 0; index < result.getTimestamps().size(); index++) {
            Long rawTimestamp = result.getTimestamps().get(index);
            Double rawEquity = result.getEquities().get(index);
            if (rawTimestamp == null || rawEquity == null) {
                throw new QTSError("Sweep curve response contains a null point");
            }
            if (index == 0 || !differential) {
                timestamp = rawTimestamp;
                equity = rawEquity;
            } else {
                timestamp += rawTimestamp;
                equity += rawEquity;
            }
            points.add(new EquityCurvePoint(timestamp, equity));
        }
        int inputCount = result.getMeta() != null && result.getMeta().getInputPointCount() != null
                ? result.getMeta().getInputPointCount() : points.size();
        boolean resampled = result.getMeta() != null && Boolean.TRUE.equals(result.getMeta().getResampled());
        return new BoundedEquityCurve(List.copyOf(points), inputCount, resampled);
    }

    /** Validate a caller-selected bounded resample count. */
    public static void validateMaxResample(int maxResample) {
        if (maxResample < 1 || maxResample > MAX_MAX_RESAMPLE) {
            throw new IllegalArgumentException("maxResample must be between 1 and " + MAX_MAX_RESAMPLE);
        }
    }
}
