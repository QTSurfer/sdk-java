package net.qtsurfer.api.sdk.internal;

import net.qtsurfer.api.sdk.internal.StatusNormalizer.Normalized;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StatusNormalizerTest {

    @Test
    void recognizesCompletedCaseInsensitive() {
        assertEquals(Normalized.COMPLETED, StatusNormalizer.normalize("completed"));
        assertEquals(Normalized.COMPLETED, StatusNormalizer.normalize("Completed"));
        assertEquals(Normalized.COMPLETED, StatusNormalizer.normalize("COMPLETED"));
    }

    @Test
    void recognizesFailed() {
        assertEquals(Normalized.FAILED, StatusNormalizer.normalize("failed"));
        assertEquals(Normalized.FAILED, StatusNormalizer.normalize("Failed"));
    }

    @Test
    void recognizesAbortedVariants() {
        assertEquals(Normalized.ABORTED, StatusNormalizer.normalize("aborted"));
        assertEquals(Normalized.ABORTED, StatusNormalizer.normalize("cancelled"));
        assertEquals(Normalized.ABORTED, StatusNormalizer.normalize("Canceled"));
    }

    @Test
    void treatsUnknownAndNullAsInProgress() {
        assertEquals(Normalized.IN_PROGRESS, StatusNormalizer.normalize(null));
        assertEquals(Normalized.IN_PROGRESS, StatusNormalizer.normalize("queued"));
        assertEquals(Normalized.IN_PROGRESS, StatusNormalizer.normalize("Started"));
        assertEquals(Normalized.IN_PROGRESS, StatusNormalizer.normalize("running"));
        assertEquals(Normalized.IN_PROGRESS, StatusNormalizer.normalize(""));
    }

    @Test
    void acceptsEnumsViaToString() {
        // OpenAPI-generated enums expose the wire value via toString(); simulate that.
        Object fakeEnum = new Object() {
            @Override public String toString() { return "Completed"; }
        };
        assertEquals(Normalized.COMPLETED, StatusNormalizer.normalize(fakeEnum));
    }
}
