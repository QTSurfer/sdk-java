package com.qtsurfer.api.sdk.workflows;

import com.qtsurfer.api.client.api.BacktestingApi;
import com.qtsurfer.api.client.model.AcceptedJob;
import com.qtsurfer.api.client.model.BacktestJobResult;
import com.qtsurfer.api.client.model.DataSourceType;
import com.qtsurfer.api.client.model.ExecuteBacktestRequest;
import com.qtsurfer.api.client.model.JobState;
import com.qtsurfer.api.client.model.PrepareRequest;
import com.qtsurfer.api.client.model.PrepareJobState;
import com.qtsurfer.api.client.model.ResultMap;
import com.qtsurfer.api.sdk.BacktestOptions;
import com.qtsurfer.api.sdk.BacktestRequest;
import com.qtsurfer.api.sdk.BacktestStage;
import com.qtsurfer.api.sdk.errors.QTSExecutionError;
import com.qtsurfer.api.sdk.errors.QTSPreparationError;
import com.qtsurfer.api.sdk.errors.QTSStrategyCompileError;
import com.qtsurfer.api.sdk.internal.CompileStatus;
import com.qtsurfer.api.sdk.internal.StatusNormalizer.Normalized;
import com.qtsurfer.api.sdk.internal.StrategyCompileClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BacktestWorkflowTest {

    @Mock BacktestingApi backtestingApi;
    @Mock StrategyCompileClient strategyClient;

    private BacktestWorkflow workflow;

    private static final BacktestRequest REQ = BacktestRequest.builder()
            .strategy("class S {}")
            .exchangeId("binance")
            .instrument("BTC/USDT")
            .from("2026-01-01T00:00:00Z")
            .to("2026-01-02T00:00:00Z")
            .build();

    private static BacktestOptions fastOpts() {
        return BacktestOptions.builder()
                .pollInterval(Duration.ofMillis(1))
                .maxPollInterval(Duration.ofMillis(2))
                .build();
    }

    @BeforeEach
    void setUp() {
        workflow = new BacktestWorkflow(strategyClient, backtestingApi, ForkJoinPool.commonPool());
    }

    @Test
    void runsHappyPathAndReturnsResultMap() throws Exception {
        when(strategyClient.submit("class S {}")).thenReturn("compile-job-1");
        when(strategyClient.status("compile-job-1"))
                .thenReturn(new CompileStatus(Normalized.COMPLETED, "strategy-abc", null));

        when(backtestingApi.prepareBacktest(eq("binance"), eq(DataSourceType.TICKER), any(PrepareRequest.class)))
                .thenReturn(new AcceptedJob().jobId("prep-1"));
        when(backtestingApi.getPrepareStatus("binance", DataSourceType.TICKER, "prep-1"))
                .thenReturn(new PrepareJobState().status(PrepareJobState.StatusEnum.COMPLETED).size(100).completed(100));

        when(backtestingApi.executeBacktest(eq("binance"), eq(DataSourceType.TICKER), any(ExecuteBacktestRequest.class)))
                .thenReturn(new AcceptedJob().jobId("exec-1"));
        ResultMap resultMap = new ResultMap()
                .strategyId("strategy-abc")
                .instrument("BTC/USDT")
                .pnlTotal(42.0);
        when(backtestingApi.getBacktestResult("binance", DataSourceType.TICKER, "exec-1"))
                .thenReturn(new BacktestJobResult()
                        .state(new JobState().status(JobState.StatusEnum.COMPLETED).size(100).completed(100))
                        .results(resultMap));

        List<BacktestStage> stages = new ArrayList<>();
        BacktestOptions opts = BacktestOptions.builder()
                .pollInterval(Duration.ofMillis(1))
                .maxPollInterval(Duration.ofMillis(2))
                .onProgress(p -> { if (!stages.contains(p.stage())) stages.add(p.stage()); })
                .build();

        ResultMap result = workflow.runFull(REQ, opts).get(10, TimeUnit.SECONDS);

        assertEquals("strategy-abc", result.getStrategyId());
        assertEquals("BTC/USDT", result.getInstrument());
        assertEquals(42.0, result.getPnlTotal());
        assertEquals(List.of(BacktestStage.COMPILING, BacktestStage.PREPARING, BacktestStage.EXECUTING), stages);

        ArgumentCaptor<ExecuteBacktestRequest> execBody = ArgumentCaptor.forClass(ExecuteBacktestRequest.class);
        verify(backtestingApi).executeBacktest(eq("binance"), eq(DataSourceType.TICKER), execBody.capture());
        assertEquals("prep-1", execBody.getValue().getPrepareJobId());
        assertEquals("strategy-abc", execBody.getValue().getStrategyId());
    }

    @Test
    void keepsPollingThroughAnEmpty202AndResolvesOnceTheResultIsReadable() throws Exception {
        // The API answers 202 with an empty body when a job is known but its result is not
        // readable yet, so a successful response can legitimately carry no state at all.
        // Dereferencing getState() in the retry predicate ends the poll instead of continuing it
        // — the exception is swallowed by the retry policy, so the caller silently receives a
        // null ResultMap for a backtest that actually completed.
        when(strategyClient.submit("class S {}")).thenReturn("compile-job-1");
        when(strategyClient.status("compile-job-1"))
                .thenReturn(new CompileStatus(Normalized.COMPLETED, "strategy-abc", null));

        when(backtestingApi.prepareBacktest(eq("binance"), eq(DataSourceType.TICKER), any(PrepareRequest.class)))
                .thenReturn(new AcceptedJob().jobId("prep-1"));
        when(backtestingApi.getPrepareStatus("binance", DataSourceType.TICKER, "prep-1"))
                .thenReturn(new PrepareJobState().status(PrepareJobState.StatusEnum.COMPLETED).size(1).completed(1));

        when(backtestingApi.executeBacktest(eq("binance"), eq(DataSourceType.TICKER), any(ExecuteBacktestRequest.class)))
                .thenReturn(new AcceptedJob().jobId("exec-202"));

        ResultMap resultMap = new ResultMap()
                .strategyId("strategy-abc")
                .instrument("BTC/USDT")
                .pnlTotal(7.0);
        when(backtestingApi.getBacktestResult("binance", DataSourceType.TICKER, "exec-202"))
                .thenReturn(new BacktestJobResult())  // 202: empty body, no state
                .thenReturn(new BacktestJobResult())
                .thenReturn(new BacktestJobResult()
                        .state(new JobState().status(JobState.StatusEnum.COMPLETED).size(1).completed(1))
                        .results(resultMap));

        BacktestOptions opts = BacktestOptions.builder()
                .pollInterval(Duration.ofMillis(1))
                .maxPollInterval(Duration.ofMillis(2))
                .build();

        ResultMap result = workflow.runFull(REQ, opts).get(10, TimeUnit.SECONDS);

        assertEquals("strategy-abc", result.getStrategyId());
        assertEquals(7.0, result.getPnlTotal());
        verify(backtestingApi, atLeast(3)).getBacktestResult("binance", DataSourceType.TICKER, "exec-202");
    }

    @Test
    void throwsQTSStrategyCompileErrorWhenSubmitFails() {
        doThrow(new QTSStrategyCompileError("bad source")).when(strategyClient).submit(anyString());

        CompletableFuture<ResultMap> future = workflow.runFull(REQ, fastOpts());
        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> future.get(5, TimeUnit.SECONDS));
        assertInstanceOf(QTSStrategyCompileError.class, ex.getCause());
    }

    @Test
    void throwsQTSStrategyCompileErrorWhenCompileStatusIsFailed() throws Exception {
        when(strategyClient.submit(anyString())).thenReturn("compile-job-1");
        when(strategyClient.status("compile-job-1"))
                .thenReturn(new CompileStatus(Normalized.FAILED, null, "syntax error line 4"));

        CompletableFuture<ResultMap> future = workflow.runFull(REQ, fastOpts());
        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> future.get(5, TimeUnit.SECONDS));
        assertInstanceOf(QTSStrategyCompileError.class, ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("syntax error"));
    }

    @Test
    void pollsCompileStatusUntilCompleted() throws Exception {
        when(strategyClient.submit(anyString())).thenReturn("compile-job-1");
        when(strategyClient.status("compile-job-1"))
                .thenReturn(new CompileStatus(Normalized.IN_PROGRESS, null, null))
                .thenReturn(new CompileStatus(Normalized.COMPLETED, "strategy-abc", null));

        when(backtestingApi.prepareBacktest(anyString(), eq(DataSourceType.TICKER), any(PrepareRequest.class)))
                .thenReturn(new AcceptedJob().jobId("prep-1"));
        when(backtestingApi.getPrepareStatus(anyString(), eq(DataSourceType.TICKER), eq("prep-1")))
                .thenReturn(new PrepareJobState().status(PrepareJobState.StatusEnum.COMPLETED).size(1).completed(1));
        when(backtestingApi.executeBacktest(anyString(), eq(DataSourceType.TICKER), any(ExecuteBacktestRequest.class)))
                .thenReturn(new AcceptedJob().jobId("exec-1"));
        when(backtestingApi.getBacktestResult(anyString(), eq(DataSourceType.TICKER), eq("exec-1")))
                .thenReturn(new BacktestJobResult()
                        .state(new JobState().status(JobState.StatusEnum.COMPLETED).size(1).completed(1))
                        .results(new ResultMap().strategyId("strategy-abc")));

        ResultMap result = workflow.runFull(REQ, fastOpts()).get(5, TimeUnit.SECONDS);
        assertEquals("strategy-abc", result.getStrategyId());
        verify(strategyClient, atLeastOnce()).status("compile-job-1");
    }

    @Test
    void throwsQTSPreparationErrorWhenPrepareStatusIsFailed() throws Exception {
        when(strategyClient.submit(anyString())).thenReturn("compile-job-1");
        when(strategyClient.status("compile-job-1"))
                .thenReturn(new CompileStatus(Normalized.COMPLETED, "strategy-abc", null));
        when(backtestingApi.prepareBacktest(anyString(), eq(DataSourceType.TICKER), any(PrepareRequest.class)))
                .thenReturn(new AcceptedJob().jobId("prep-1"));
        when(backtestingApi.getPrepareStatus(anyString(), eq(DataSourceType.TICKER), eq("prep-1")))
                .thenReturn(new PrepareJobState()
                        .status(PrepareJobState.StatusEnum.FAILED)
                        .statusDetail("data not available"));

        CompletableFuture<ResultMap> future = workflow.runFull(REQ, fastOpts());
        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> future.get(5, TimeUnit.SECONDS));
        assertInstanceOf(QTSPreparationError.class, ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("data not available"));
    }

    @Test
    void throwsQTSExecutionErrorWhenExecutionStateIsFailed() throws Exception {
        when(strategyClient.submit(anyString())).thenReturn("compile-job-1");
        when(strategyClient.status("compile-job-1"))
                .thenReturn(new CompileStatus(Normalized.COMPLETED, "strategy-abc", null));
        when(backtestingApi.prepareBacktest(anyString(), eq(DataSourceType.TICKER), any(PrepareRequest.class)))
                .thenReturn(new AcceptedJob().jobId("prep-1"));
        when(backtestingApi.getPrepareStatus(anyString(), eq(DataSourceType.TICKER), eq("prep-1")))
                .thenReturn(new PrepareJobState().status(PrepareJobState.StatusEnum.COMPLETED).size(1).completed(1));
        when(backtestingApi.executeBacktest(anyString(), eq(DataSourceType.TICKER), any(ExecuteBacktestRequest.class)))
                .thenReturn(new AcceptedJob().jobId("exec-1"));
        when(backtestingApi.getBacktestResult(anyString(), eq(DataSourceType.TICKER), eq("exec-1")))
                .thenReturn(new BacktestJobResult()
                        .state(new JobState().status(JobState.StatusEnum.FAILED).statusDetail("worker crashed"))
                        .results(new ResultMap()));

        CompletableFuture<ResultMap> future = workflow.runFull(REQ, fastOpts());
        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> future.get(5, TimeUnit.SECONDS));
        assertInstanceOf(QTSExecutionError.class, ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("worker crashed"));
    }

    // Cancellation via the shortcut `runFull` future is intentionally not supported
    // (the future is composed via thenCompose; cancellation doesn't propagate back to
    // the underlying Backtest). Consumers who need cancellation should use the
    // decomposed API: see DomainObjectsTest#cancelTransitionsJobStateAndFiresServerCancel.
    @org.junit.jupiter.api.Disabled("Moved to DomainObjectsTest — cancellation is on Backtest, not on the runFull shortcut")
    @Test
    void cancelTriggersServerSideCancelBacktestWhenExecuteStageReached() throws Exception {
        when(strategyClient.submit(anyString())).thenReturn("compile-job-1");
        when(strategyClient.status("compile-job-1"))
                .thenReturn(new CompileStatus(Normalized.COMPLETED, "strategy-abc", null));
        when(backtestingApi.prepareBacktest(anyString(), eq(DataSourceType.TICKER), any(PrepareRequest.class)))
                .thenReturn(new AcceptedJob().jobId("prep-1"));
        when(backtestingApi.getPrepareStatus(anyString(), eq(DataSourceType.TICKER), eq("prep-1")))
                .thenReturn(new PrepareJobState().status(PrepareJobState.StatusEnum.COMPLETED).size(1).completed(1));
        when(backtestingApi.executeBacktest(anyString(), eq(DataSourceType.TICKER), any(ExecuteBacktestRequest.class)))
                .thenReturn(new AcceptedJob().jobId("exec-abort"));
        lenient().when(backtestingApi.getBacktestResult(anyString(), eq(DataSourceType.TICKER), eq("exec-abort")))
                .thenReturn(new BacktestJobResult()
                        .state(new JobState().status(JobState.StatusEnum.STARTED).size(100).completed(10))
                        .results(new ResultMap()));

        CompletableFuture<ResultMap> future = workflow.runFull(REQ, fastOpts());
        Thread.sleep(150);
        future.cancel(true);

        assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));

        long deadline = System.currentTimeMillis() + 2_000;
        Throwable lastError = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                verify(backtestingApi, atLeastOnce())
                        .cancelBacktest("binance", DataSourceType.TICKER, "exec-abort");
                return;
            } catch (AssertionError ae) {
                lastError = ae;
                Thread.sleep(50);
            }
        }
        if (lastError != null) throw (AssertionError) lastError;
    }
}
