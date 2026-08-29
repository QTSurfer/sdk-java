package com.qtsurfer.api.sdk;

import com.qtsurfer.api.client.model.EquityCurveMeta;
import com.qtsurfer.api.client.model.EquityCurveOutMode;
import com.qtsurfer.api.client.model.EquityCurveResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedEquityCurveTest {

    @Test
    void decodesDifferentialShortCurveToAbsolutePoints() {
        EquityCurveResult result = new EquityCurveResult()
                .timestamps(List.of(1_000L, 100L, 100L))
                .equities(List.of(100.0, 1.5, -0.5))
                .meta(new EquityCurveMeta().inputPointCount(20).resampled(true)
                        .differential(true).outMode(EquityCurveOutMode.SHORT));

        BoundedEquityCurve curve = BoundedEquityCurve.decode(result, 1_000);

        assertEquals(List.of(
                new EquityCurvePoint(1_000L, 100.0),
                new EquityCurvePoint(1_100L, 101.5),
                new EquityCurvePoint(1_200L, 101.0)), curve.points());
        assertEquals(20, curve.inputPointCount());
        assertTrue(curve.resampled());
    }

    @Test
    void rejectsCallerLimitAboveAbsoluteCeiling() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> BoundedEquityCurve.validateMaxResample(10_001));
        assertTrue(error.getMessage().contains("10000"));
    }
}
