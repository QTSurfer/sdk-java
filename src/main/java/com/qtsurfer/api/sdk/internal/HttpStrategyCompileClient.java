package com.qtsurfer.api.sdk.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.qtsurfer.api.client.invoker.ApiClient;
import com.qtsurfer.api.client.invoker.ApiException;
import com.qtsurfer.api.sdk.errors.QTSStrategyCompileError;
import com.qtsurfer.api.sdk.CompiledStrategy;
import com.qtsurfer.api.client.model.DeclaredProperty;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Objects;
import java.util.ArrayList;
import java.util.List;

/**
 * Default {@link StrategyCompileClient} backed by the api-client's shared
 * {@link ApiClient} (HttpClient, ObjectMapper, base URI, request interceptor).
 *
 * <p>The SDK bypasses the generated {@code StrategyApi} and reads the JSON body
 * directly, so a wire-casing drift on this response never breaks strict enum
 * deserialization in generated types — the same defensive posture applied to
 * the rest of the workflow's status handling.</p>
 */
public final class HttpStrategyCompileClient implements StrategyCompileClient {

    private final ApiClient apiClient;

    public HttpStrategyCompileClient(ApiClient apiClient) {
        this.apiClient = Objects.requireNonNull(apiClient, "apiClient");
    }

    @Override
    public String compile(String source) {
        return compileDetails(source).id();
    }

    @Override
    public CompiledStrategy compileDetails(String source) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(apiClient.getBaseUri() + "/strategy"))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(source));
        applyInterceptor(builder);

        HttpResponse<String> response = send(builder, "Strategy compilation failed");
        checkStatus(response, "Strategy compilation failed");

        JsonNode json = parse(response.body(), "Invalid compile response");
        if (!json.hasNonNull("strategyId")) {
            throw new QTSStrategyCompileError("Compile response missing strategyId");
        }
        List<DeclaredProperty> properties = new ArrayList<>();
        JsonNode declared = json.get("declaredProperties");
        if (declared != null && declared.isArray()) {
            for (JsonNode node : declared) {
                try {
                    properties.add(apiClient.getObjectMapper().treeToValue(node, DeclaredProperty.class));
                } catch (IOException e) {
                    throw new QTSStrategyCompileError("Invalid declaredProperties in compile response", e);
                }
            }
        }
        return new CompiledStrategy(json.get("strategyId").asText(), properties);
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
        int status = response.statusCode();
        if (status < 400) return;

        String body = response.body();
        String detail = ": HTTP " + status + (body != null && !body.isBlank() ? " — " + body : "");

        // A 429 says the platform is holding too many compilations at once — the source was
        // never judged. Reported under its own wording so it is not read as a compiler
        // diagnostic, which is what every other 4xx on this endpoint means.
        if (status == 429) {
            throw new QTSStrategyCompileError("Strategy was not compiled, too many compilations in flight; retry later" + detail);
        }
        // A 401 also means the source was never judged — the call never got as far as the
        // compiler. This endpoint is called directly rather than through the generated
        // client, so it must carry an ApiException cause itself for
        // AuthenticatedClient's refresh-on-401 policy to recognize and retry it, the same
        // way every other call in this SDK does.
        if (status == 401) {
            throw new QTSStrategyCompileError(context + detail, new ApiException(status, context + detail));
        }
        throw new QTSStrategyCompileError(context + detail);
    }

    private JsonNode parse(String body, String context) {
        try {
            return apiClient.getObjectMapper().readTree(body);
        } catch (IOException e) {
            throw new QTSStrategyCompileError(context, e);
        }
    }
}
