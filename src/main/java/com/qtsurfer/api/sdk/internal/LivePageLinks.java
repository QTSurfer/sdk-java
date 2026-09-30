package com.qtsurfer.api.sdk.internal;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Parses server-provided live pagination links without exposing link parsing to callers. */
public final class LivePageLinks {
    private LivePageLinks() {}

    /** Decode the query parameters from a HAL continuation link. */
    public static Map<String, String> queryParameters(String href) {
        String query = URI.create(href).getRawQuery();
        if (query == null) return Map.of();
        Map<String, String> parameters = new LinkedHashMap<>();
        for (String part : query.split("&")) {
            int separator = part.indexOf('=');
            if (separator < 0) continue;
            String key = URLDecoder.decode(part.substring(0, separator), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(part.substring(separator + 1), StandardCharsets.UTF_8);
            parameters.put(key, value);
        }
        return Map.copyOf(parameters);
    }
}
