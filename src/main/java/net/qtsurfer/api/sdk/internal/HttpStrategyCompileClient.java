package net.qtsurfer.api.sdk.internal;

import com.fasterxml.jackson.databind.JsonNode;
import net.qtsurfer.api.client.invoker.ApiClient;
import net.qtsurfer.api.sdk.errors.QTSStrategyCompileError;
import net.qtsurfer.api.sdk.internal.StatusNormalizer.Normalized;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Objects;

/**
 * Default {@link StrategyCompileClient} backed by the api-client's shared
 * {@link ApiClient} (HttpClient, ObjectMapper, base URI, request interceptor).
 *
 * <p>The SDK bypasses the generated {@code StrategyApi} for both endpoints
 * because the strict enum deserialization in the generated response types does
 * not tolerate the wire casing the backend currently uses (e.g. {@code "completed"}).
 * Reading the JSON body directly and normalizing via {@link StatusNormalizer}
 * keeps the workflow stable across that drift.</p>
 */
public final class HttpStrategyCompileClient implements StrategyCompileClient {

    private final ApiClient apiClient;

    public HttpStrategyCompileClient(ApiClient apiClient) {
        this.apiClient = Objects.requireNonNull(apiClient, "apiClient");
    }

    @Override
    public String submit(String source) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(apiClient.getBaseUri() + "/strategy"))
                .header("Content-Type", "text/plain")
                .header("X-Compile-Async", "true")
                .POST(HttpRequest.BodyPublishers.ofString(source));
        applyInterceptor(builder);

        HttpResponse<String> response = send(builder, "Strategy submission failed");
        checkStatus(response, "Strategy submission failed");

        JsonNode json = parse(response.body(), "Invalid compile submit response");
        if (!json.hasNonNull("jobId")) {
            throw new QTSStrategyCompileError("Compile submit response missing jobId");
        }
        return json.get("jobId").asText();
    }

    @Override
    public CompileStatus status(String jobId) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(apiClient.getBaseUri() + "/strategy/" + jobId))
                .GET();
        applyInterceptor(builder);

        HttpResponse<String> response = send(builder, "Compile status request failed");
        checkStatus(response, "Compile status request failed");

        JsonNode json = parse(response.body(), "Invalid compile status response");
        Normalized normalized = StatusNormalizer.normalize(
                json.hasNonNull("status") ? json.get("status").asText() : null);
        String strategyId = json.hasNonNull("strategyId") ? json.get("strategyId").asText() : null;
        String statusDetail = json.hasNonNull("statusDetail") ? json.get("statusDetail").asText() : null;
        return new CompileStatus(normalized, strategyId, statusDetail);
    }

    private void applyInterceptor(HttpRequest.Builder builder) {
        if (apiClient.getRequestInterceptor() != null) {
            apiClient.getRequestInterceptor().accept(builder);
        }
    }

    private HttpResponse<String> send(HttpRequest.Builder builder, String onFailure) {
        try {
            return apiClient.getHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new QTSStrategyCompileError(onFailure + " (interrupted)", e);
        } catch (IOException e) {
            throw new QTSStrategyCompileError(onFailure, e);
        }
    }

    private static void checkStatus(HttpResponse<String> response, String context) {
        if (response.statusCode() >= 400) {
            String body = response.body();
            throw new QTSStrategyCompileError(
                    context + ": HTTP " + response.statusCode()
                            + (body != null && !body.isBlank() ? " — " + body : ""));
        }
    }

    private JsonNode parse(String body, String context) {
        try {
            return apiClient.getObjectMapper().readTree(body);
        } catch (IOException e) {
            throw new QTSStrategyCompileError(context, e);
        }
    }
}
