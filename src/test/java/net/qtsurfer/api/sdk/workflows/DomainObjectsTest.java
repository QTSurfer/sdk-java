package net.qtsurfer.api.sdk.workflows;

import net.qtsurfer.api.client.api.BacktestingApi;
import net.qtsurfer.api.client.model.AcceptedJob;
import net.qtsurfer.api.client.model.BacktestJobResult;
import net.qtsurfer.api.client.model.DataSourceType;
import net.qtsurfer.api.client.model.ExecuteBacktestingRequest;
import net.qtsurfer.api.client.model.JobState;
import net.qtsurfer.api.client.model.PrepareBacktestingRequest;
import net.qtsurfer.api.client.model.ResultMap;
import net.qtsurfer.api.sdk.Backtest;
import net.qtsurfer.api.sdk.BacktestOptions;
import net.qtsurfer.api.sdk.BacktestProgress;
import net.qtsurfer.api.sdk.BacktestRequest;
import net.qtsurfer.api.sdk.BacktestStage;
import net.qtsurfer.api.sdk.Strategy;
import net.qtsurfer.api.sdk.internal.CompileStatus;
import net.qtsurfer.api.sdk.internal.StatusNormalizer.Normalized;
import net.qtsurfer.api.sdk.internal.StrategyCompileClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DomainObjectsTest {

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
    void compileReturnsReusableStrategyHandle() throws Exception {
        when(strategyClient.submit(anyString())).thenReturn("compile-job-1");
        when(strategyClient.status("compile-job-1"))
                .thenReturn(new CompileStatus(Normalized.COMPLETED, "strategy-abc", null));

        Strategy s = workflow.compile("class S {}", fastOpts()).get(5, TimeUnit.SECONDS);
        assertEquals("strategy-abc", s.id());
    }

    @Test
    void strategyBacktestReturnsJobAndAwaitCompletes() throws Exception {
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
                        .state(new JobState().status(JobState.StatusEnum.COMPLETED).size(100).completed(100))
                        .results(new ResultMap().strategyId("strategy-abc").instrument("BTC/USDT")));

        Strategy s = workflow.compile("class S {}", fastOpts()).get(5, TimeUnit.SECONDS);
        Backtest job = s.backtest(REQ, fastOpts()).get(5, TimeUnit.SECONDS);

        assertEquals("exec-1", job.id());
        assertEquals(s, job.strategy());
        ResultMap result = job.await().get(5, TimeUnit.SECONDS);
        assertEquals("strategy-abc", result.getStrategyId());
        assertEquals(Backtest.State.COMPLETED, job.state());
    }

    @Test
    void jobProgressPublisherEmitsExecutingEvents() throws Exception {
        when(strategyClient.submit(anyString())).thenReturn("compile-job-1");
        when(strategyClient.status("compile-job-1"))
                .thenReturn(new CompileStatus(Normalized.COMPLETED, "strategy-abc", null));
        when(backtestingApi.prepareBacktesting(anyString(), eq(DataSourceType.TICKER), any(PrepareBacktestingRequest.class)))
                .thenReturn(new AcceptedJob().jobId("prep-1"));
        when(backtestingApi.getPreparationStatus(anyString(), eq(DataSourceType.TICKER), eq("prep-1")))
                .thenReturn(new JobState().status(JobState.StatusEnum.COMPLETED).size(1).completed(1));
        when(backtestingApi.executeBacktesting(anyString(), eq(DataSourceType.TICKER), any(ExecuteBacktestingRequest.class)))
                .thenReturn(new AcceptedJob().jobId("exec-2"));
        when(backtestingApi.getExecutionResult(anyString(), eq(DataSourceType.TICKER), eq("exec-2")))
                .thenReturn(new BacktestJobResult()
                        .state(new JobState().status(JobState.StatusEnum.STARTED).size(100).completed(25))
                        .results(new ResultMap()))
                .thenReturn(new BacktestJobResult()
                        .state(new JobState().status(JobState.StatusEnum.COMPLETED).size(100).completed(100))
                        .results(new ResultMap().strategyId("strategy-abc")));

        Strategy s = workflow.compile("class S {}", fastOpts()).get(5, TimeUnit.SECONDS);
        Backtest job = s.backtest(REQ, fastOpts()).get(5, TimeUnit.SECONDS);

        List<BacktestProgress> seen = new ArrayList<>();
        CountDownLatch complete = new CountDownLatch(1);
        job.progress().subscribe(new Flow.Subscriber<>() {
            Flow.Subscription sub;
            @Override public void onSubscribe(Flow.Subscription s) { sub = s; s.request(Long.MAX_VALUE); }
            @Override public void onNext(BacktestProgress p) { seen.add(p); }
            @Override public void onError(Throwable t) { complete.countDown(); }
            @Override public void onComplete() { complete.countDown(); }
        });

        job.await().get(5, TimeUnit.SECONDS);
        assertTrue(complete.await(2, TimeUnit.SECONDS), "publisher terminated");
        assertTrue(seen.stream().anyMatch(p -> p.stage() == BacktestStage.EXECUTING),
                "at least one EXECUTING progress event emitted");
    }

    @Test
    void cancelTransitionsJobStateAndFiresServerCancel() throws Exception {
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

        Strategy s = workflow.compile("class S {}", fastOpts()).get(5, TimeUnit.SECONDS);
        Backtest job = s.backtest(REQ, fastOpts()).get(5, TimeUnit.SECONDS);

        Thread.sleep(50);
        boolean first = job.cancel();
        boolean second = job.cancel();
        assertTrue(first, "first cancel transitions state");
        assertEquals(false, second);
        assertEquals(Backtest.State.CANCELED, job.state());

        AtomicReference<Throwable> captured = new AtomicReference<>();
        try { job.await().get(5, TimeUnit.SECONDS); } catch (Exception e) { captured.set(e); }
        assertTrue(captured.get() != null, "await completes exceptionally after cancel");

        long deadline = System.currentTimeMillis() + 2_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                org.mockito.Mockito.verify(backtestingApi, atLeastOnce())
                        .cancelExecution("binance", DataSourceType.TICKER, "exec-abort");
                return;
            } catch (AssertionError e) {
                Thread.sleep(50);
            }
        }
        org.mockito.Mockito.verify(backtestingApi, atLeastOnce())
                .cancelExecution("binance", DataSourceType.TICKER, "exec-abort");
    }

    @Test
    void runFullShortcutDelegatesToDecomposedApi() throws Exception {
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
                        .state(new JobState().status(JobState.StatusEnum.COMPLETED).size(100).completed(100))
                        .results(new ResultMap().strategyId("strategy-abc")));

        ResultMap result = workflow.runFull(REQ, fastOpts()).get(5, TimeUnit.SECONDS);
        assertEquals("strategy-abc", result.getStrategyId());
    }
}
