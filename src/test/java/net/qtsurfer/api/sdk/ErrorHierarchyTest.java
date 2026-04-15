package net.qtsurfer.api.sdk;

import net.qtsurfer.api.sdk.errors.QTSCanceledError;
import net.qtsurfer.api.sdk.errors.QTSError;
import net.qtsurfer.api.sdk.errors.QTSExecutionError;
import net.qtsurfer.api.sdk.errors.QTSPreparationError;
import net.qtsurfer.api.sdk.errors.QTSStrategyCompileError;
import net.qtsurfer.api.sdk.errors.QTSTimeoutError;
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
}
