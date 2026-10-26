package com.qtsurfer.api.sdk;

import com.qtsurfer.api.sdk.errors.QTSCanceledError;
import com.qtsurfer.api.sdk.errors.QTSError;
import com.qtsurfer.api.client.invoker.ApiException;
import com.qtsurfer.api.sdk.errors.QTSExecutionError;
import com.qtsurfer.api.sdk.errors.QTSPreparationError;
import com.qtsurfer.api.sdk.errors.QTSStrategyCompileError;
import com.qtsurfer.api.sdk.errors.QTSTimeoutError;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

class ErrorHierarchyTest {

    @Test
    void everySubclassIsInstanceOfQTSErrorAndKeepsCause() {
        Throwable cause = new IllegalStateException("root");
        assertInstanceOf(QTSError.class, new QTSStrategyCompileError("m", cause));
        assertInstanceOf(QTSError.class, new QTSPreparationError("m", cause));
        assertInstanceOf(QTSError.class, new QTSExecutionError("m", cause));
        assertInstanceOf(QTSError.class, new QTSTimeoutError("m", cause));
        assertInstanceOf(QTSError.class, new QTSCanceledError("m", cause));

        QTSPreparationError err = new QTSPreparationError("boom", cause);
        assertEquals("boom", err.getMessage());
        assertSame(cause, err.getCause());
    }

    @Test
    void subclassesAreDisjoint() {
        Object err = new QTSPreparationError("m");
        assertFalse(err instanceof QTSExecutionError);
        assertFalse(err instanceof QTSStrategyCompileError);
        assertFalse(err instanceof QTSTimeoutError);
        assertFalse(err instanceof QTSCanceledError);
    }

    @Test
    void exposesRequiredScopeAndRetryabilityFromTypedApiErrors() {
        QTSError scopeError = new QTSError("forbidden", new ApiException(403, "Forbidden", null,
                "{\"code\":\"insufficient_scope\",\"message\":\"Missing grant\","
                        + "\"required\":\"datasets:write\"}"));
        assertEquals("datasets:write", scopeError.getRequiredScope().orElseThrow());
        assertEquals("forbidden (required scope: datasets:write)", scopeError.getMessage());
        assertEquals("insufficient_scope", scopeError.getInsufficientScopeError().orElseThrow()
                .getCode().getValue());

        QTSError retryableError = new QTSError("unavailable", new ApiException(503, "Unavailable", null,
                "{\"code\":503,\"message\":\"Try again\",\"retryable\":true}"));
        assertEquals(true, retryableError.getRetryable().orElseThrow());
    }

    @Test
    void exposesPlanRequiredErrorAndOptionalUpgradeUrl() {
        QTSError error = new QTSError("live run refused", new ApiException(402, "Payment Required", null,
                "{\"code\":402,\"message\":\"Live runs are not included in tier free.\","
                        + "\"retryable\":false,\"upgradeUrl\":\"https://example.com/upgrade\"}"));

        assertEquals(402, error.getStatusCode().orElseThrow());
        assertEquals(false, error.getRetryable().orElseThrow());
        assertEquals("https://example.com/upgrade", error.getUpgradeUrl().orElseThrow().toString());
        assertEquals(402, error.getPlanRequiredError().orElseThrow().getCode());
    }
}
