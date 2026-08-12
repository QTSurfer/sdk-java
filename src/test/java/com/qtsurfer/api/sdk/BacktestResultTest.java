package com.qtsurfer.api.sdk;

import com.qtsurfer.api.client.model.JobState;
import com.qtsurfer.api.client.model.ResultMap;
import com.qtsurfer.api.sdk.errors.QTSError;
import com.qtsurfer.api.sdk.errors.QTSExecutionError;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the standalone {@code backtestResult(exchangeId, jobId)}
 * read — the route to a run this process did not start. All HTTP traffic is
 * served by a local {@link HttpServer} on a free port — no live network calls.
 */
class BacktestResultTest {

    private HttpServer server;
    private QTSurfer qts;
    private final List<HttpExchange> exchanges = new ArrayList<>();
    private final AtomicReference<String> responseBody = new AtomicReference<>("{}");
    private final AtomicReference<Integer> responseStatus = new AtomicReference<>(200);

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchanges.add(exchange);
            byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus.get(), body.length == 0 ? -1 : body.length);
            try (var os = exchange.getResponseBody()) {
                if (body.length > 0) os.write(body);
            }
        });
        server.start();
        qts = QTSurfer.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .token("test-token")
                .build();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void backtestResultGetsTheExecuteJobResource() {
        responseStatus.set(200);
        responseBody.set("""
                {"state":{"status":"Completed","size":10,"completed":10},\
                "results":{"instrument":"BTC/USDT","strategyId":"strategy-abc",\
                "pnlTotal":42.5,"totalTrades":7}}""");

        BacktestOutcome outcome = qts.backtestResult("binance", "exec-1");

        HttpExchange recorded = exchanges.get(0);
        assertEquals("GET", recorded.getRequestMethod());
        assertEquals("/backtest/binance/ticker/execute/exec-1", recorded.getRequestURI().getPath());
        assertEquals("Bearer test-token", recorded.getRequestHeaders().getFirst("Authorization"));

        ResultMap result = assertInstanceOf(BacktestOutcome.Completed.class, outcome).results();
        assertTrue(outcome.finished());
        assertEquals(JobState.StatusEnum.COMPLETED, outcome.state().getStatus());
        assertEquals("BTC/USDT", result.getInstrument());
        assertEquals("strategy-abc", result.getStrategyId());
        assertEquals(42.5, result.getPnlTotal());
        assertEquals(7L, result.getTotalTrades());
    }

    /**
     * The reason this returns a sealed outcome rather than the numbers alone.
     * A run that failed is the answer to "what happened to this job?", so it
     * arrives as a value — with the platform's account of why — and not as the
     * {@code QTSExecutionError} the polling path would have raised.
     */
    @Test
    void aFailedRunIsAnAnswerNotAnError() {
        responseStatus.set(200);
        responseBody.set("""
                {"state":{"status":"Failed","statusDetail":"strategy threw on tick 4",\
                "size":10,"completed":4},"results":{"strategyId":"strategy-abc"}}""");

        BacktestOutcome outcome = qts.backtestResult("binance", "exec-1");

        BacktestOutcome.Failed failed = assertInstanceOf(BacktestOutcome.Failed.class, outcome);
        assertTrue(failed.finished(), "finishing badly is still finishing");
        assertEquals("strategy threw on tick 4", failed.state().getStatusDetail());
        // Whatever the run produced before it died is carried, not dropped.
        assertEquals("strategy-abc", failed.results().getStrategyId());
    }

    /** Cancellation is likewise reported, and kept distinct from failure. */
    @Test
    void anAbortedRunIsReportedAsAbortedNotFailed() {
        responseStatus.set(200);
        responseBody.set("{\"state\":{\"status\":\"Aborted\",\"size\":10,\"completed\":6}}");

        BacktestOutcome outcome = qts.backtestResult("binance", "exec-1");

        assertInstanceOf(BacktestOutcome.Aborted.class, outcome);
        assertTrue(outcome.finished());
        assertEquals(JobState.StatusEnum.ABORTED, outcome.state().getStatus());
    }

    /**
     * The whole point of the standalone read: one HTTP call, no compile, no
     * prepare, no execute. Nothing is submitted on the way to a job id the
     * caller was simply handed.
     */
    @Test
    void backtestResultSubmitsNothing() {
        responseStatus.set(200);
        responseBody.set("{\"state\":{\"status\":\"Completed\"},\"results\":{\"pnlTotal\":1.0}}");

        qts.backtestResult("binance", "exec-1");

        assertEquals(1, exchanges.size());
        assertTrue(exchanges.stream().allMatch(e -> "GET".equals(e.getRequestMethod())));
    }

    /** A run still going carries its progress, and is not mistaken for a verdict. */
    @Test
    void aRunStillInFlightIsReportedAsInProgress() {
        responseStatus.set(200);
        responseBody.set("{\"state\":{\"status\":\"Started\",\"size\":10,\"completed\":3}}");

        BacktestOutcome outcome = qts.backtestResult("binance", "exec-1");

        assertInstanceOf(BacktestOutcome.InProgress.class, outcome);
        assertFalse(outcome.finished());
        assertEquals(10, outcome.state().getSize());
        assertEquals(3, outcome.state().getCompleted());
    }

    /**
     * The API answers a job it knows about but cannot describe yet with a
     * {@code 202} and an empty body, so the response object itself is absent.
     * Classification has to survive that rather than dereference it — and it
     * must land on the one variant that tolerates a missing state, not on a
     * terminal one whose null check would then trip.
     */
    @Test
    void a202WithNoBodyReadsAsInProgressWithNoState() {
        responseStatus.set(202);
        responseBody.set("");

        BacktestOutcome outcome = qts.backtestResult("binance", "exec-1");

        assertInstanceOf(BacktestOutcome.InProgress.class, outcome);
        assertFalse(outcome.finished());
        assertNull(outcome.state(), "the platform sent nothing to describe the job with");
        assertNull(outcome.results());
    }

    /**
     * An unrecognised status is not evidence a run finished. Treating it as
     * in-progress keeps a caller asking rather than reading a verdict off a
     * status this SDK does not know — the same default the poll loop takes.
     */
    @Test
    void anUnknownStatusIsTreatedAsStillInProgress() {
        responseStatus.set(200);
        responseBody.set("{\"state\":{\"status\":\"New\"}}");

        assertInstanceOf(
                BacktestOutcome.InProgress.class, qts.backtestResult("binance", "exec-1"));
    }

    /**
     * An id the platform does not recognise for this caller — or a real id
     * paired with the wrong exchange, which is indistinguishable from here.
     *
     * <p>Raised as a plain {@link QTSError}, and deliberately <em>not</em> as
     * {@link QTSExecutionError}: nothing this read can raise is about an
     * execution. Failure and abortion are values here, so every throw left is
     * transport or HTTP, and a caller catching the subtype to mean "my run
     * blew up" would be catching the wrong thing.
     */
    @Test
    void anUnknownJobSurfacesAsQtsError() {
        responseStatus.set(404);
        responseBody.set("{\"code\":\"NOT_FOUND\",\"message\":\"no such job\"}");

        QTSError ex = assertThrows(
                QTSError.class, () -> qts.backtestResult("binance", "nope"));
        assertFalse(ex instanceof QTSExecutionError, "a read never fails an execution");
        assertTrue(ex.getMessage().contains("Execution result request failed"));
        assertTrue(ex.getMessage().contains("HTTP 404"));
        assertTrue(ex.getMessage().contains("NOT_FOUND"));
    }

    @Test
    void backtestResultRejectsNullExchangeId() {
        assertThrows(NullPointerException.class, () -> qts.backtestResult(null, "exec-1"));
    }

    @Test
    void backtestResultRejectsNullJobId() {
        assertThrows(NullPointerException.class, () -> qts.backtestResult("binance", null));
    }
}
