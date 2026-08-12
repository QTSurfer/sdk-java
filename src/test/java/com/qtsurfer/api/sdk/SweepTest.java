package com.qtsurfer.api.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qtsurfer.api.client.model.ExecuteSweepResult;
import com.qtsurfer.api.client.model.SweepRunRow;
import com.qtsurfer.api.client.model.SweepSensitivity;
import com.qtsurfer.api.client.model.WalkForwardResult;
import com.qtsurfer.api.sdk.errors.QTSExecutionError;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the sweep workflow and the {@link Sweep} handle. All HTTP
 * traffic is served by a local {@link HttpServer} on a free port — no live
 * network calls.
 */
class SweepTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String COMPILE_BODY = "{\"strategyId\":\"strategy-abc\"}";
    private static final String PREPARE_ACCEPTED = "{\"jobId\":\"prep-1\"}";
    private static final String PREPARE_DONE =
            "{\"contextId\":\"ctx-1\",\"status\":\"Completed\",\"size\":10,\"completed\":10,"
                    + "\"coverageRatio\":0.98}";
    private static final String SWEEP_ACCEPTED = """
            {"sweepId":"swp-1","requestId":"prep-1","totalRuns":44,"shards":4,\
            "seed":487221,"queued":true}""";

    private HttpServer server;
    private QTSurfer qts;

    private final List<Recorded> recorded = new ArrayList<>();
    private final Deque<String> sweepResults = new ArrayDeque<>();
    private final AtomicReference<String> lastSweepResult = new AtomicReference<>();
    private final AtomicReference<String> sweepAcceptedBody = new AtomicReference<>(SWEEP_ACCEPTED);
    private final AtomicInteger sweepAcceptedStatus = new AtomicInteger(202);
    private final AtomicReference<String> sensitivityBody = new AtomicReference<>("{}");
    private final AtomicReference<String> cancelledResult = new AtomicReference<>();
    private final AtomicBoolean cancelRequested = new AtomicBoolean();

    /** One recorded HTTP call: enough to assert on the route and on what was sent. */
    private record Recorded(String method, String path, String query, String body) {}

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            recorded.add(new Recorded(method, path, exchange.getRequestURI().getQuery(), body));
            respond(exchange, route(method, path));
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

    private Response route(String method, String path) {
        if (path.endsWith("/strategy")) return new Response(200, COMPILE_BODY);
        if (path.endsWith("/prepare")) return new Response(202, PREPARE_ACCEPTED);
        if (path.contains("/prepare/")) return new Response(200, PREPARE_DONE);
        if (path.endsWith("/sensitivity")) return new Response(200, sensitivityBody.get());
        if ("POST".equals(method)) return new Response(sweepAcceptedStatus.get(), sweepAcceptedBody.get());
        if ("DELETE".equals(method)) {
            cancelRequested.set(true);
            return new Response(200, "{\"status\":\"cancelling\",\"sweepId\":\"swp-1\"}");
        }
        if (cancelRequested.get() && cancelledResult.get() != null) {
            return new Response(200, cancelledResult.get());
        }
        String next = sweepResults.poll();
        if (next != null) lastSweepResult.set(next);
        return new Response(200, Optional.ofNullable(lastSweepResult.get()).orElse("{}"));
    }

    private record Response(int status, String body) {}

    private static void respond(HttpExchange exchange, Response response) throws IOException {
        byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(response.status(), bytes.length == 0 ? -1 : bytes.length);
        try (var os = exchange.getResponseBody()) {
            if (bytes.length > 0) os.write(bytes);
        }
    }

    private static SweepOptions.Builder fastOpts() {
        return SweepOptions.builder()
                .pollInterval(Duration.ofMillis(5))
                .maxPollInterval(Duration.ofMillis(10));
    }

    private static SweepRequest.Builder request() {
        return SweepRequest.builder()
                .strategy("class S {}")
                .exchangeId("binance")
                .instrument("BTC/USDT")
                .from("2026-01-01T00:00:00Z")
                .to("2026-02-01T00:00:00Z")
                .param("rsiPeriod", ParamAxis.range(7, 28, 1))
                .param("useTrendFilter", ParamAxis.of(true, false));
    }

    private Recorded call(String method, String pathSuffix) {
        return recorded.stream()
                .filter(r -> r.method().equals(method) && r.path().endsWith(pathSuffix))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no " + method + " ending in " + pathSuffix + " in " + recorded));
    }

    private static String progress(long done, int total) {
        return "{\"done\":" + done + ",\"total\":" + total + ",\"aborted\":0,\"shardCount\":4,"
                + "\"pendingShards\":0,\"failedShards\":0,\"retrying\":0,\"notStarted\":0}";
    }

    private static String row(int runIx, double sharpe) {
        return "{\"runIx\":" + runIx + ",\"rank\":1,\"params\":{\"rsiPeriod\":14},\"sharpe\":" + sharpe
                + ",\"sortino\":2.0,\"pnl\":100.0,\"pnlPct\":1.0,\"cagr\":0.5,\"maxDdPct\":3.0,"
                + "\"trades\":40,\"winRate\":0.6,\"belowTradeFloor\":false,\"aborted\":false,"
                + "\"runtimeMs\":120}";
    }

    // ---- submission ----

    /**
     * The one mechanical unknown worth pinning: both axis shapes are {@code oneOf}
     * wrappers in the generated client, and getting them wrong produces a body that
     * still serializes — just not into a grid.
     */
    @Test
    void sendsTheGridAsTheApiSpellsIt() throws Exception {
        sweepResults.add(completed());

        qts.sweep(request().objective(SweepObjective.SORTINO)
                        .sampler(SweepSampler.LHS)
                        .samples(100)
                        .seed(487221L)
                        .build(),
                fastOpts().build())
                .get(10, TimeUnit.SECONDS)
                .await().get(10, TimeUnit.SECONDS);

        Recorded submit = call("POST", "/executeSweep/prep-1");
        assertEquals("/backtest/binance/ticker/executeSweep/prep-1", submit.path());

        JsonNode body = MAPPER.readTree(submit.body());
        assertEquals("strategy-abc", body.get("strategyId").asText());
        JsonNode sweep = body.get("sweep");
        assertEquals("lhs", sweep.get("sampler").asText());
        assertEquals("sortino", sweep.get("objective").asText());
        assertEquals(100, sweep.get("samples").asInt());
        assertEquals(487221L, sweep.get("seed").asLong());

        JsonNode range = sweep.get("params").get("rsiPeriod");
        assertEquals(7.0, range.get("from").asDouble());
        assertEquals(28.0, range.get("to").asDouble());
        assertEquals(1.0, range.get("step").asDouble());
        assertFalse(range.has("values"), "a range axis must not also carry a values list");
        // The oneOf wrapper must flatten, not nest: the axis is the object itself.
        assertTrue(submit.body().contains("\"rsiPeriod\":{\"from\":7.0,\"to\":28.0,\"step\":1.0}"),
                "unexpected range encoding in " + submit.body());

        JsonNode values = sweep.get("params").get("useTrendFilter");
        assertTrue(values.get("values").isArray());
        assertTrue(values.get("values").get(0).isBoolean());
        assertTrue(values.get("values").get(0).asBoolean());
        assertFalse(values.get("values").get(1).asBoolean());
    }

    /** Preparation runs first, and the sweep is addressed by the dataset it produced. */
    @Test
    void preparesTheDatasetBeforeSubmitting() throws Exception {
        sweepResults.add(completed());
        List<BacktestStage> stages = new ArrayList<>();

        Sweep sweep = qts.sweep(request().build(),
                        fastOpts().onProgress(p -> {
                            if (stages.isEmpty() || stages.get(stages.size() - 1) != p.stage()) {
                                stages.add(p.stage());
                            }
                        }).build())
                .get(10, TimeUnit.SECONDS);
        sweep.await().get(10, TimeUnit.SECONDS);

        assertEquals("/backtest/binance/ticker/prepare", call("POST", "/prepare").path());
        assertEquals("prep-1", sweep.requestId());
        assertEquals("strategy-abc", sweep.strategyId());
        assertEquals(List.of(BacktestStage.COMPILING, BacktestStage.PREPARING, BacktestStage.EXECUTING),
                stages);
    }

    /** The prepared window's coverage reaches the caller: a sweep scores a thin window N times. */
    @Test
    void reportsPrepareCoverageOnTheFinalPreparingEvent() throws Exception {
        sweepResults.add(completed());
        List<SweepProgressEvent> events = new ArrayList<>();

        qts.sweep(request().build(), fastOpts().onProgress(events::add).build())
                .get(10, TimeUnit.SECONDS)
                .await().get(10, TimeUnit.SECONDS);

        Double coverage = events.stream()
                .filter(e -> e.stage() == BacktestStage.PREPARING)
                .map(SweepProgressEvent::coverageRatio)
                .filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
        assertEquals(0.98, coverage);
    }

    /** Acceptance answers the seed and the dedupe question before any row exists. */
    @Test
    void acceptanceCarriesTheSeedAndWhetherAnythingWasEnqueued() throws Exception {
        sweepAcceptedBody.set("""
                {"sweepId":"swp-1","requestId":"prep-1","totalRuns":44,"shards":4,\
                "seed":99,"queued":false}""");
        sweepResults.add(completed());

        Sweep sweep = qts.sweep(request().build(), fastOpts().build()).get(10, TimeUnit.SECONDS);

        assertEquals("swp-1", sweep.id());
        assertEquals(99L, sweep.accepted().getSeed());
        assertEquals(44, sweep.accepted().getTotalRuns());
        assertFalse(sweep.accepted().getQueued(), "an identical sweep already existed");
        sweep.await().get(10, TimeUnit.SECONDS);
    }

    @Test
    void rejectedSubmissionSurfacesAsExecutionError() {
        sweepAcceptedStatus.set(400);
        sweepAcceptedBody.set("{\"code\":\"BAD_REQUEST\",\"message\":\"grid exceeds the server limit\"}");

        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> qts.sweep(request().build(), fastOpts().build()).get(10, TimeUnit.SECONDS));

        assertInstanceOf(QTSExecutionError.class, ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("HTTP 400"));
        assertTrue(ex.getCause().getMessage().contains("grid exceeds the server limit"));
    }

    @Test
    void rejectsAnEmptyGrid() {
        assertThrows(IllegalArgumentException.class, () -> SweepRequest.builder()
                .strategy("class S {}").exchangeId("binance").instrument("BTC/USDT")
                .from("2026-01-01").to("2026-02-01")
                .build());
    }

    @Test
    void rejectsASingleFoldWalkForward() {
        assertThrows(IllegalArgumentException.class, () -> WalkForwardSpec.of(1));
    }

    // ---- polling the leaderboard ----

    @Test
    void pollsUntilTheSweepStopsAdvancing() throws Exception {
        sweepResults.add(running(10, 44));
        sweepResults.add(running(30, 44));
        sweepResults.add(completed());
        List<SweepProgressEvent> events = new ArrayList<>();

        ExecuteSweepResult result = qts.sweep(request().build(),
                        fastOpts().onProgress(events::add).build())
                .get(10, TimeUnit.SECONDS)
                .await().get(10, TimeUnit.SECONDS);

        assertEquals(ExecuteSweepResult.StatusEnum.COMPLETED, result.getStatus());
        assertEquals(1, result.getLeaderboard().size());
        assertEquals(0.12, result.getPbo());

        // Percent comes from the run counts, never from the shard counts beside them.
        List<Double> percents = events.stream()
                .filter(e -> e.stage() == BacktestStage.EXECUTING && e.percent() != null)
                .map(SweepProgressEvent::percent).toList();
        assertTrue(percents.contains(10.0 / 44 * 100.0), "expected a run-level percent, got " + percents);
        assertTrue(events.stream()
                .anyMatch(e -> e.snapshot() != null && e.snapshot().getFailedShards() != null));
    }

    /**
     * {@code PARTIAL} is terminal — a sweep that lost a shard is finished and its
     * rows are readable. Normalizing it as "still running" polls a dead sweep forever,
     * which is why this asserts a resolution rather than a value.
     */
    @Test
    void treatsPartialAsFinished() throws Exception {
        sweepResults.add(partial());

        ExecuteSweepResult result = qts.sweep(request().build(), fastOpts().build())
                .get(10, TimeUnit.SECONDS)
                .await().get(10, TimeUnit.SECONDS);

        assertEquals(ExecuteSweepResult.StatusEnum.PARTIAL, result.getStatus());
        // No sweep-wide failed status exists: a sweep whose every shard died looks
        // exactly like this, with nothing on the leaderboard.
        assertEquals(0, result.getLeaderboardSize());
        assertTrue(result.getLeaderboard().isEmpty());
    }

    /** The default sends no preference; the platform's own defaults (ranked, plateau) apply. */
    @Test
    void sendsNoRankingOrOrderUnlessAsked() throws Exception {
        sweepResults.add(completed());

        qts.sweep(request().build(), fastOpts().build())
                .get(10, TimeUnit.SECONDS)
                .await().get(10, TimeUnit.SECONDS);

        Recorded poll = call("GET", "/executeSweep/prep-1/swp-1");
        assertNull(poll.query(), "no ranking or order parameter should be sent by default");
    }

    /**
     * The natural view is the only route to rows the ranked view dropped, which is
     * what {@code truncated} reports.
     */
    @Test
    void sendsTheRequestedOrder() throws Exception {
        sweepResults.add("""
                {"sweepId":"swp-1","status":"COMPLETED","objective":"sharpe","order":"natural",\
                "ranking":"raw","progress":%s,"leaderboardSize":2,"truncated":false,\
                "leaderboard":[%s,%s]}""".formatted(progress(44, 44), row(0, 1.0), row(1, 2.0)));

        ExecuteSweepResult result = qts.sweep(request().build(),
                        fastOpts().order(SweepOrder.NATURAL).ranking(SweepRanking.PLATEAU).build())
                .get(10, TimeUnit.SECONDS)
                .await().get(10, TimeUnit.SECONDS);

        String query = call("GET", "/executeSweep/prep-1/swp-1").query();
        assertTrue(query.contains("order=natural"), "unexpected query: " + query);
        // Ranking is still sent — the platform ignores it on this view rather than
        // rejecting it, and says so by answering `raw`.
        assertTrue(query.contains("ranking=plateau"), "unexpected query: " + query);
        assertEquals(ExecuteSweepResult.OrderEnum.NATURAL, result.getOrder());
        assertEquals(ExecuteSweepResult.RankingEnum.RAW, result.getRanking());
    }

    @Test
    void sendsTheRequestedRanking() throws Exception {
        sweepResults.add(completed());

        ExecuteSweepResult result = qts.sweep(request().build(),
                        fastOpts().ranking(SweepRanking.RAW).build())
                .get(10, TimeUnit.SECONDS)
                .await().get(10, TimeUnit.SECONDS);

        assertTrue(call("GET", "/executeSweep/prep-1/swp-1").query().contains("ranking=raw"));
        // What was asked for is not always what was applied; the response is the authority.
        assertEquals(ExecuteSweepResult.RankingEnum.PLATEAU, result.getRanking());
    }

    // ---- re-reading the leaderboard ----

    /**
     * Reading another view is a read, not a re-run: the rows the ranked view capped
     * away are reachable from the handle the sweep already produced.
     */
    @Test
    void resultsRereadsTheLeaderboardInAnotherView() throws Exception {
        sweepResults.add("""
                {"sweepId":"swp-1","status":"COMPLETED","objective":"sharpe","order":"ranked",\
                "ranking":"plateau","progress":%s,"leaderboardSize":2,"truncated":true,\
                "leaderboard":[%s]}""".formatted(progress(44, 44), row(3, 2.4)));

        Sweep sweep = qts.sweep(request().build(), fastOpts().build()).get(10, TimeUnit.SECONDS);
        ExecuteSweepResult ranked = sweep.await().get(10, TimeUnit.SECONDS);
        assertTrue(ranked.getTruncated(), "the display view dropped rows");
        assertEquals(1, ranked.getLeaderboard().size());

        sweepResults.add("""
                {"sweepId":"swp-1","status":"COMPLETED","objective":"sharpe","order":"natural",\
                "ranking":"raw","progress":%s,"leaderboardSize":2,"truncated":false,\
                "leaderboard":[%s,%s]}"""
                .formatted(progress(44, 44), row(0, 1.0), row(1, 2.0)));

        ExecuteSweepResult all = sweep.results(SweepOrder.NATURAL);

        assertEquals(ExecuteSweepResult.OrderEnum.NATURAL, all.getOrder());
        assertEquals(2, all.getLeaderboard().size(), "the rows the ranked view dropped");
        assertFalse(all.getTruncated());

        Recorded reread = recorded.stream()
                .filter(r -> "GET".equals(r.method()) && r.path().endsWith("/swp-1"))
                .reduce((first, second) -> second)
                .orElseThrow();
        assertTrue(reread.query().contains("order=natural"), "unexpected query: " + reread.query());
    }

    /** The re-read must not compile, prepare or submit anything a second time. */
    @Test
    void resultsDoesNotSubmitASecondSweep() throws Exception {
        sweepResults.add(completed());

        Sweep sweep = qts.sweep(request().build(), fastOpts().build()).get(10, TimeUnit.SECONDS);
        sweep.await().get(10, TimeUnit.SECONDS);

        long writesBefore = writeCalls();
        int callsBefore = recorded.size();

        sweepResults.add(completed());
        sweep.results(SweepOrder.NATURAL, SweepRanking.RAW);

        assertEquals(writesBefore, writeCalls(),
                "a re-read must not compile, prepare or submit again");
        assertEquals(callsBefore + 1, recorded.size(), "exactly one extra request");
        Recorded last = recorded.get(recorded.size() - 1);
        assertEquals("GET", last.method());
        assertEquals("/backtest/binance/ticker/executeSweep/prep-1/swp-1", last.path());
    }

    /** Every request that creates work server-side: compile, prepare, and submit. */
    private long writeCalls() {
        return recorded.stream().filter(r -> "POST".equals(r.method())).count();
    }

    /** A plateau score with no neighbours behind it is unevidenced, not confirmed. */
    @Test
    void keepsPlateauScoreAndNeighbourCountTogether() throws Exception {
        sweepResults.add("""
                {"sweepId":"swp-1","status":"COMPLETED","objective":"sharpe","order":"ranked",\
                "ranking":"plateau","progress":%s,"leaderboardSize":1,"truncated":false,\
                "leaderboard":[{"runIx":3,"rank":1,"params":{"rsiPeriod":14},"plateauScore":1.1,\
                "neighbourCount":0,"deflatedSharpe":0.42,"sharpe":3.9,"sortino":2.0,"pnl":100.0,\
                "pnlPct":1.0,"cagr":0.5,"maxDdPct":3.0,"trades":40,"winRate":0.6,\
                "belowTradeFloor":false,"aborted":false,"runtimeMs":120}]}"""
                .formatted(progress(44, 44)));

        SweepRunRow top = qts.sweep(request().build(), fastOpts().build())
                .get(10, TimeUnit.SECONDS)
                .await().get(10, TimeUnit.SECONDS)
                .getLeaderboard().get(0);

        assertEquals(1.1, top.getPlateauScore());
        assertEquals(0, top.getNeighbourCount());
        assertEquals(0.42, top.getDeflatedSharpe());
    }

    // ---- cancellation ----

    /**
     * Cancelling a sweep does not throw the finished rows away. The handle diverges
     * from {@link Backtest} here on purpose: the poll keeps running until the platform
     * reports {@code CANCELLED}, and {@code await()} then resolves with what was scored
     * before the stop.
     */
    @Test
    void cancelResolvesWithTheRowsAlreadyScored() throws Exception {
        lastSweepResult.set(running(10, 44));
        cancelledResult.set("""
                {"sweepId":"swp-1","status":"CANCELLED","objective":"sharpe","order":"ranked",\
                "ranking":"plateau","progress":%s,"leaderboardSize":1,"truncated":false,\
                "leaderboard":[%s]}""".formatted(progress(10, 44), row(2, 1.5)));

        Sweep sweep = qts.sweep(request().build(), fastOpts().build()).get(10, TimeUnit.SECONDS);
        assertEquals(Sweep.State.EXECUTING, sweep.state());
        assertTrue(sweep.cancel());
        assertFalse(sweep.cancel(), "a second cancel changes nothing");

        ExecuteSweepResult result = sweep.await().get(10, TimeUnit.SECONDS);

        assertEquals(ExecuteSweepResult.StatusEnum.CANCELLED, result.getStatus());
        assertEquals(1, result.getLeaderboard().size(), "rows scored before the stop stay readable");
        assertEquals(Sweep.State.CANCELED, sweep.state());
        assertEquals("/backtest/binance/ticker/executeSweep/prep-1/swp-1",
                call("DELETE", "/swp-1").path());
    }

    // ---- walk-forward ----

    /**
     * A walk-forward sweep answers in a different shape, and the discriminator is
     * available from acceptance — before a single fold has finished.
     */
    @Test
    void walkForwardIsRequestedAndIdentifiableFromAcceptance() throws Exception {
        sweepAcceptedBody.set("""
                {"sweepId":"swp-1","requestId":"prep-1","totalRuns":44,"shards":4,"seed":7,\
                "queued":true,"walkForward":{"folds":4,"inSamplePct":70,"totalRuns":180}}""");
        sweepResults.add("""
                {"sweepId":"swp-1","status":"COMPLETED","objective":"sharpe","order":"ranked",\
                "ranking":"raw","progress":%s,"leaderboardSize":2,"truncated":false,\
                "leaderboard":[%s,%s],"walkForward":{"folds":4,"inSamplePct":70,\
                "completedFolds":2,"results":[]}}"""
                .formatted(progress(180, 180), row(0, 1.1), row(1, 0.4)));

        Sweep sweep = qts.sweep(
                        request().walkForward(WalkForwardSpec.of(4, 70)).build(),
                        fastOpts().build())
                .get(10, TimeUnit.SECONDS);

        JsonNode submitted = MAPPER.readTree(call("POST", "/executeSweep/prep-1").body());
        assertEquals(4, submitted.get("walkForward").get("folds").asInt());
        assertEquals(70, submitted.get("walkForward").get("inSamplePct").asInt());
        assertNotNull(sweep.accepted().getWalkForward(), "branchable before any fold finishes");
        assertEquals(180, sweep.accepted().getWalkForward().getTotalRuns());

        ExecuteSweepResult result = sweep.await().get(10, TimeUnit.SECONDS);
        WalkForwardResult wf = result.getWalkForward();
        assertNotNull(wf);
        assertEquals(2, wf.getCompletedFolds());
        // Absent is not zero: no drift figure could be computed, and a placeholder
        // would be indistinguishable from winners that never moved.
        assertNull(wf.getParamDrift());
        // runIx is the fold index here, not a position in the grid.
        assertEquals(0, result.getLeaderboard().get(0).getRunIx());
        assertEquals(1, result.getLeaderboard().get(1).getRunIx());
        // Never plateau-ranked, whatever was requested; no sweep-wide overfitting figure.
        assertEquals(ExecuteSweepResult.RankingEnum.RAW, result.getRanking());
        assertNull(result.getPbo());
    }

    // ---- sensitivity ----

    /**
     * The truncation flag has to reach the caller: a short heatmap list otherwise
     * reads as "these are all the interactions".
     */
    @Test
    void sensitivitySurfacesTruncation() throws Exception {
        sweepResults.add(completed());
        sensitivityBody.set("""
                {"sweepId":"swp-1","status":"COMPLETED","objective":"sortino","rowsAnalysed":44,\
                "marginals":[{"param":"rsiPeriod","points":[{"value":14,"count":4,"best":2.0,\
                "mean":1.0,"worst":0.1}]}],"heatmaps":[],"heatmapsTruncated":true}""");

        Sweep sweep = qts.sweep(request().build(), fastOpts().build()).get(10, TimeUnit.SECONDS);
        sweep.await().get(10, TimeUnit.SECONDS);

        SweepSensitivity sensitivity = sweep.sensitivity(SweepObjective.SORTINO);

        assertTrue(sensitivity.getHeatmapsTruncated(),
                "an empty heatmap list must not be mistaken for the whole interaction set");
        assertEquals(1, sensitivity.getMarginals().size(), "marginals are always complete");
        assertEquals(44, sensitivity.getRowsAnalysed());

        Recorded read = call("GET", "/sensitivity");
        assertEquals("/backtest/binance/ticker/executeSweep/prep-1/swp-1/sensitivity", read.path());
        assertTrue(read.query().contains("objective=sortino"));
    }

    @Test
    void sensitivityDefaultsToTheSweepsOwnObjective() throws Exception {
        sweepResults.add(completed());
        sensitivityBody.set("{\"sweepId\":\"swp-1\",\"heatmapsTruncated\":false}");

        Sweep sweep = qts.sweep(request().build(), fastOpts().build()).get(10, TimeUnit.SECONDS);
        sweep.await().get(10, TimeUnit.SECONDS);
        sweep.sensitivity();

        assertNull(call("GET", "/sensitivity").query());
    }

    // ---- response fixtures ----

    private static String running(long done, int total) {
        return """
                {"sweepId":"swp-1","status":"RUNNING","objective":"sharpe","order":"ranked",\
                "ranking":"plateau","progress":%s,"leaderboardSize":0,"truncated":false,\
                "leaderboard":[]}""".formatted(progress(done, total));
    }

    private static String completed() {
        return """
                {"sweepId":"swp-1","status":"COMPLETED","objective":"sharpe","order":"ranked",\
                "ranking":"plateau","pbo":0.12,"pboSplits":16,"progress":%s,"leaderboardSize":1,\
                "truncated":false,"leaderboard":[%s]}"""
                .formatted(progress(44, 44), row(3, 2.4));
    }

    private static String partial() {
        return """
                {"sweepId":"swp-1","status":"PARTIAL","objective":"sharpe","order":"ranked",\
                "ranking":"plateau","progress":%s,"leaderboardSize":0,"truncated":false,\
                "leaderboard":[]}"""
                .formatted("{\"done\":0,\"total\":44,\"aborted\":0,\"shardCount\":4,"
                        + "\"pendingShards\":0,\"failedShards\":4,\"retrying\":0,\"notStarted\":0}");
    }
}
