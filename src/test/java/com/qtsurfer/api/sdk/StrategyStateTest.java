package com.qtsurfer.api.sdk;

import com.qtsurfer.api.client.api.StrategyApi;
import com.qtsurfer.api.client.invoker.ApiClient;
import com.qtsurfer.api.client.invoker.ApiResponse;
import com.qtsurfer.api.client.model.StrategySummary;
import com.qtsurfer.api.client.model.StrategyState;
import com.qtsurfer.api.sdk.errors.QTSError;
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
 * Unit tests for {@code validateStrategy} / {@code strategyState} /
 * {@code listStrategies} / {@code deleteStrategy} / {@code getStrategyCode}.
 * All HTTP traffic is served by a local {@link HttpServer} on a free port —
 * no live network calls.
 */
class StrategyStateTest {

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

    // ---- validateStrategy ----

    @Test
    void validateStrategyPostsToTheValidateSubresource() {
        responseStatus.set(200);
        responseBody.set("{\"strategyId\":\"s1\",\"validation\":\"passed\"}");

        qts.validateStrategy("s1");

        HttpExchange recorded = exchanges.get(0);
        assertEquals("POST", recorded.getRequestMethod());
        assertEquals("/strategy/s1/validate", recorded.getRequestURI().getPath());
        assertEquals("Bearer test-token", recorded.getRequestHeaders().getFirst("Authorization"));
    }

    /** A 202 means this call queued a check — that and the id is all it says. */
    @Test
    void a202IsReportedAsQueued() {
        responseStatus.set(202);
        responseBody.set("{\"strategyId\":\"s1\",\"validation\":\"pending\"}");

        ValidationOutcome outcome = qts.validateStrategy("s1");

        assertTrue(outcome.queued());
        assertInstanceOf(ValidationOutcome.Queued.class, outcome);
        assertEquals("s1", outcome.strategyId());
    }

    /**
     * The id on a {@code Queued} outcome is the caller's own, not one read
     * back off the 202 body — the body is not trusted to carry one.
     */
    @Test
    void queuedEchoesTheCallersIdNotTheResponseBody() {
        responseStatus.set(202);
        responseBody.set("{\"strategyId\":\"someone-elses-id\",\"validation\":\"pending\"}");

        assertEquals("s1", qts.validateStrategy("s1").strategyId());
    }

    /**
     * Regression guard on a load-bearing assumption: the generated client
     * binds the 200 schema ({@code StrategyState}) for both branches, so a
     * 202 body has to deserialize cleanly into one or {@code validateStrategy}
     * would fail outright. The SDK does not expose that parsed body — it
     * reads the status instead — but the parse still has to succeed.
     */
    @Test
    void a202BodyStillDeserializesIntoStrategyState() throws Exception {
        responseStatus.set(202);
        responseBody.set("{\"strategyId\":\"s1\",\"validation\":\"pending\"}");

        ApiClient client = new ApiClient();
        client.updateBaseUri("http://127.0.0.1:" + server.getAddress().getPort());
        ApiResponse<StrategyState> raw =
                new StrategyApi(client).validateStrategyWithHttpInfo("s1");

        assertEquals(202, raw.getStatusCode());
        assertEquals(StrategyState.ValidationEnum.PENDING, raw.getData().getValidation());
        // The 202 body carries nothing else; the verdict fields stay absent.
        assertNull(raw.getData().getValidatedAt());
        assertNull(raw.getData().getDetail());
    }

    @Test
    void a200IsReportedAsNotQueuedAndCarriesTheFullState() {
        responseStatus.set(200);
        responseBody.set("""
                {"strategyId":"s1","validation":"failed",\
                "compiledAt":"2026-08-04T16:23:04Z","validatedAt":"2026-08-04T16:24:11Z",\
                "detail":"no default constructor","dryRunIncomplete":false,\
                "notices":[{"level":"WARN","code":"indicator.bar-data-on-ticker-path",\
                "message":"Indicator requires bar data but is on the ticker path",\
                "provenance":"compile-dry-run"}]}""");

        ValidationOutcome outcome = qts.validateStrategy("s1");

        assertFalse(outcome.queued());
        StrategyState state = assertInstanceOf(ValidationOutcome.NotQueued.class, outcome).state();
        assertEquals("s1", outcome.strategyId());
        assertEquals(StrategyState.ValidationEnum.FAILED, state.getValidation());
        assertEquals("no default constructor", state.getDetail());
        assertEquals(1, state.getNotices().size());
        assertEquals("WARN", state.getNotices().get(0).getLevel());
    }

    /**
     * The case the two-question split exists for: nothing was queued by this
     * call, yet there is still no verdict — a check an earlier call started
     * is outstanding. {@code queued() == false} must not be read as "decided".
     */
    @Test
    void a200CanCarryPendingFromACheckAnEarlierCallQueued() {
        responseStatus.set(200);
        responseBody.set("{\"strategyId\":\"s1\",\"validation\":\"pending\"}");

        ValidationOutcome outcome = qts.validateStrategy("s1");

        assertFalse(outcome.queued(), "this call started nothing");
        StrategyState state = assertInstanceOf(ValidationOutcome.NotQueued.class, outcome).state();
        assertEquals(StrategyState.ValidationEnum.PENDING, state.getValidation(),
                "yet there is no verdict either");
    }

    @Test
    void aNotQueuedResponseWithNoBodySurfacesAsQtsError() {
        responseStatus.set(200);
        responseBody.set("");

        QTSError ex = assertThrows(QTSError.class, () -> qts.validateStrategy("s1"));
        assertTrue(ex.getMessage().contains("carried no strategy state"));
    }

    @Test
    void validateStrategyMapsApiExceptionToQtsError() {
        responseStatus.set(404);
        responseBody.set("{\"code\":\"NOT_FOUND\",\"message\":\"no such strategy\"}");

        QTSError ex = assertThrows(QTSError.class, () -> qts.validateStrategy("nope"));
        assertTrue(ex.getMessage().contains("validateStrategy call failed"));
        assertTrue(ex.getMessage().contains("HTTP 404"));
        assertTrue(ex.getMessage().contains("NOT_FOUND"));
    }

    @Test
    void validateStrategyRejectsNullStrategyId() {
        assertThrows(NullPointerException.class, () -> qts.validateStrategy(null));
    }

    // ---- strategyState ----

    @Test
    void strategyStateGetsTheStrategyResource() {
        responseStatus.set(200);
        responseBody.set("{\"strategyId\":\"s1\",\"validation\":\"not_validated\"}");

        StrategyState state = qts.strategyState("s1");

        HttpExchange recorded = exchanges.get(0);
        assertEquals("GET", recorded.getRequestMethod());
        assertEquals("/strategy/s1", recorded.getRequestURI().getPath());
        assertEquals(StrategyState.ValidationEnum.NOT_VALIDATED, state.getValidation());
    }

    @Test
    void strategyStateReportsASupersededVerdictViaTimestamps() {
        responseStatus.set(200);
        responseBody.set("""
                {"strategyId":"s1","validation":"passed",\
                "validatedAt":"2026-08-04T16:24:11Z",\
                "compiledAt":"2026-08-05T09:00:00Z",\
                "requiredSources":["Ticker"],"dryRunIncomplete":true}""");

        StrategyState state = qts.strategyState("s1");

        // Recompiled after the verdict was recorded: the verdict describes
        // bytecode that is no longer what would run.
        assertTrue(state.getCompiledAt().isAfter(state.getValidatedAt()));
        assertEquals(StrategyState.ValidationEnum.PASSED, state.getValidation());
        assertTrue(state.getDryRunIncomplete());
        assertEquals(
                List.of(StrategyState.RequiredSourcesEnum.TICKER),
                state.getRequiredSources());
    }

    @Test
    void strategyStateMapsApiExceptionToQtsError() {
        responseStatus.set(404);
        responseBody.set("{\"code\":\"NOT_FOUND\",\"message\":\"no such strategy\"}");

        QTSError ex = assertThrows(QTSError.class, () -> qts.strategyState("nope"));
        assertTrue(ex.getMessage().contains("strategyState call failed"));
        assertTrue(ex.getMessage().contains("HTTP 404"));
    }

    @Test
    void strategyStateRejectsNullStrategyId() {
        assertThrows(NullPointerException.class, () -> qts.strategyState(null));
    }

    // ---- listStrategies ----

    @Test
    void listStrategiesGetsTheStrategiesResource() {
        responseStatus.set(200);
        responseBody.set("""
                {"strategies":[\
                {"strategyId":"s1","compiledAt":"2026-08-19T10:15:00Z","requiredSources":["Ticker"]},\
                {"strategyId":"s2","compiledAt":"2026-08-12T09:02:11Z"}]}""");

        List<StrategySummary> strategies = qts.listStrategies();

        HttpExchange recorded = exchanges.get(0);
        assertEquals("GET", recorded.getRequestMethod());
        assertEquals("/strategies", recorded.getRequestURI().getPath());
        assertEquals(2, strategies.size());
        assertEquals("s1", strategies.get(0).getStrategyId());
        assertEquals(List.of("Ticker"), strategies.get(0).getRequiredSources());
        assertEquals("s2", strategies.get(1).getStrategyId());
    }

    /** Never a 404 — an empty array means no registered strategies. */
    @Test
    void listStrategiesReturnsEmptyListRatherThanFailing() {
        responseStatus.set(200);
        responseBody.set("{\"strategies\":[]}");

        assertTrue(qts.listStrategies().isEmpty());
    }

    @Test
    void listStrategiesMapsApiExceptionToQtsError() {
        responseStatus.set(500);
        responseBody.set("{\"code\":\"INTERNAL\",\"message\":\"boom\"}");

        QTSError ex = assertThrows(QTSError.class, () -> qts.listStrategies());
        assertTrue(ex.getMessage().contains("listStrategies call failed"));
        assertTrue(ex.getMessage().contains("HTTP 500"));
    }

    // ---- deleteStrategy ----

    @Test
    void deleteStrategyDeletesTheStrategyResource() {
        responseStatus.set(200);
        responseBody.set("{\"strategyId\":\"s1\",\"deleted\":true}");

        qts.deleteStrategy("s1");

        HttpExchange recorded = exchanges.get(0);
        assertEquals("DELETE", recorded.getRequestMethod());
        assertEquals("/strategy/s1", recorded.getRequestURI().getPath());
    }

    @Test
    void deleteStrategyMapsApiExceptionToQtsError() {
        responseStatus.set(404);
        responseBody.set("{\"code\":\"NOT_FOUND\",\"message\":\"no such strategy\"}");

        QTSError ex = assertThrows(QTSError.class, () -> qts.deleteStrategy("nope"));
        assertTrue(ex.getMessage().contains("deleteStrategy call failed"));
        assertTrue(ex.getMessage().contains("HTTP 404"));
    }

    @Test
    void deleteStrategyRejectsNullStrategyId() {
        assertThrows(NullPointerException.class, () -> qts.deleteStrategy(null));
    }

    // ---- getStrategyCode ----

    @Test
    void getStrategyCodeGetsTheCodeSubresource() {
        responseStatus.set(200);
        responseBody.set("{\"strategyId\":\"s1\",\"code\":\"class S {}\"}");

        String code = qts.getStrategyCode("s1");

        HttpExchange recorded = exchanges.get(0);
        assertEquals("GET", recorded.getRequestMethod());
        assertEquals("/strategy/s1/code", recorded.getRequestURI().getPath());
        assertEquals("class S {}", code);
    }

    /**
     * The endpoint's 404 covers two different situations without
     * distinguishing them; from the SDK's side both just surface as
     * QTSError, same as any other 404.
     */
    @Test
    void getStrategyCodeMapsApiExceptionToQtsError() {
        responseStatus.set(404);
        responseBody.set("{\"code\":\"NOT_FOUND\",\"message\":\"nothing to return\"}");

        QTSError ex = assertThrows(QTSError.class, () -> qts.getStrategyCode("nope"));
        assertTrue(ex.getMessage().contains("getStrategyCode call failed"));
        assertTrue(ex.getMessage().contains("HTTP 404"));
    }

    @Test
    void getStrategyCodeRejectsNullStrategyId() {
        assertThrows(NullPointerException.class, () -> qts.getStrategyCode(null));
    }
}
