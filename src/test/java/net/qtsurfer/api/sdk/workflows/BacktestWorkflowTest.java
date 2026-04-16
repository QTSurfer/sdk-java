package net.qtsurfer.api.sdk.workflows;

import net.qtsurfer.api.client.api.BacktestingApi;
import net.qtsurfer.api.client.model.AcceptedJob;
import net.qtsurfer.api.client.model.BacktestJobResult;
import net.qtsurfer.api.client.model.DataSourceType;
import net.qtsurfer.api.client.model.ExecuteBacktestingRequest;
import net.qtsurfer.api.client.model.JobState;
import net.qtsurfer.api.client.model.PrepareBacktestingRequest;
import net.qtsurfer.api.client.model.ResultMap;
import net.qtsurfer.api.sdk.BacktestOptions;
import net.qtsurfer.api.sdk.BacktestRequest;
import net.qtsurfer.api.sdk.BacktestStage;
import net.qtsurfer.api.sdk.errors.QTSExecutionError;
import net.qtsurfer.api.sdk.errors.QTSPreparationError;
import net.qtsurfer.api.sdk.errors.QTSStrategyCompileError;
import net.qtsurfer.api.sdk.internal.CompileStatus;
import net.qtsurfer.api.sdk.internal.StatusNormalizer.Normalized;
import net.qtsurfer.api.sdk.internal.StrategyCompileClient;
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

        when(backtestingApi.prepareBacktesting(eq("binance"), eq(DataSourceType.TICKER), any(PrepareBacktestingRequest.class)))
                .thenReturn(new AcceptedJob().jobId("prep-1"));
        when(backtestingApi.getPreparationStatus("binance", DataSourceType.TICKER, "prep-1"))
                .thenReturn(new JobState().status(JobState.StatusEnum.COMPLETED).size(100).completed(100));

        when(backtestingApi.executeBacktesting(eq("binance"), eq(DataSourceType.TICKER), any(ExecuteBacktestingRequest.class)))
                .thenReturn(new AcceptedJob().jobId("exec-1"));
        ResultMap resultMap = new ResultMap()
                .strategyId("strategy-abc")
                .instrument("BTC/USDT")
                .pnlTotal(42.0);
        when(backtestingApi.getExecutionResult("binance", DataSourceType.TICKER, "exec-1"))
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

        ArgumentCaptor<ExecuteBacktestingRequest> execBody = ArgumentCaptor.forClass(ExecuteBacktestingRequest.class);
        verify(backtestingApi).executeBacktesting(eq("binance"), eq(DataSourceType.TICKER), execBody.capture());
        assertEquals("prep-1", execBody.getValue().getPrepareJobId());
        assertEquals("strategy-abc", execBody.getValue().getStrategyId());
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

        when(backtestingApi.prepareBacktesting(anyString(), eq(DataSourceType.TICKER), any(PrepareBacktestingRequest.class)))
                .thenReturn(new AcceptedJob().jobId("prep-1"));
        when(backtestingApi.getPreparationStatus(anyString(), eq(DataSourceType.TICKER), eq("prep-1")))
                .thenReturn(new JobState().status(JobState.StatusEnum.COMPLETED).size(1).completed(1));
        when(backtestingApi.executeBacktesting(anyString(), eq(DataSourceType.TICKER), any(ExecuteBacktestingRequest.class)))
                .thenReturn(new AcceptedJob().jobId("exec-1"));
        when(backtestingApi.getExecutionResult(anyString(), eq(DataSourceType.TICKER), eq("exec-1")))
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
        when(backtestingApi.prepareBacktesting(anyString(), eq(DataSourceType.TICKER), any(PrepareBacktestingRequest.class)))
                .thenReturn(new AcceptedJob().jobId("prep-1"));
        when(backtestingApi.getPreparationStatus(anyString(), eq(DataSourceType.TICKER), eq("prep-1")))
                .thenReturn(new JobState()
                        .status(JobState.StatusEnum.FAILED)
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
        when(backtestingApi.prepareBacktesting(anyString(), eq(DataSourceType.TICKER), any(PrepareBacktestingRequest.class)))
                .thenReturn(new AcceptedJob().jobId("prep-1"));
        when(backtestingApi.getPreparationStatus(anyString(), eq(DataSourceType.TICKER), eq("prep-1")))
                .thenReturn(new JobState().status(JobState.StatusEnum.COMPLETED).size(1).completed(1));
        when(backtestingApi.executeBacktesting(anyString(), eq(DataSourceType.TICKER), any(ExecuteBacktestingRequest.class)))
                .thenReturn(new AcceptedJob().jobId("exec-1"));
        when(backtestingApi.getExecutionResult(anyString(), eq(DataSourceType.TICKER), eq("exec-1")))
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
    void cancelTriggersServerSideCancelExecutionWhenExecuteStageReached() throws Exception {
        when(strategyClient.submit(anyString())).thenReturn("compile-job-1");
        when(strategyClient.status("compile-job-1"))
                .thenReturn(new CompileStatus(Normalized.COMPLETED, "strategy-abc", null));
        when(backtestingApi.prepareBacktesting(anyString(), eq(DataSourceType.TICKER), any(PrepareBacktestingRequest.class)))
                .thenReturn(new AcceptedJob().jobId("prep-1"));
        when(backtestingApi.getPreparationStatus(anyString(), eq(DataSourceType.TICKER), eq("prep-1")))
                .thenReturn(new JobState().status(JobState.StatusEnum.COMPLETED).size(1).completed(1));
        when(backtestingApi.executeBacktesting(anyString(), eq(DataSourceType.TICKER), any(ExecuteBacktestingRequest.class)))
                .thenReturn(new AcceptedJob().jobId("exec-abort"));
        lenient().when(backtestingApi.getExecutionResult(anyString(), eq(DataSourceType.TICKER), eq("exec-abort")))
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
                        .cancelExecution("binance", DataSourceType.TICKER, "exec-abort");
                return;
            } catch (AssertionError ae) {
                lastError = ae;
                Thread.sleep(50);
            }
        }
        if (lastError != null) throw (AssertionError) lastError;
    }
}
