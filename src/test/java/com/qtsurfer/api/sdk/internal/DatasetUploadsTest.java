package com.qtsurfer.api.sdk.internal;

import com.qtsurfer.api.client.model.DatasetCreated;
import com.qtsurfer.api.client.model.DatasetUploadSession;
import com.qtsurfer.api.client.model.DatasetUploadTarget;
import com.qtsurfer.api.sdk.errors.QTSUploadError;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DatasetUploadsTest {

    @Test
    void streamsExactBytesWithoutApiCredentials() throws Exception {
        AtomicReference<byte[]> body = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> apiKey = new AtomicReference<>();
        HttpServer server = server(exchange -> {
            body.set(exchange.getRequestBody().readAllBytes());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            apiKey.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        Path file = Files.createTempFile("dataset-upload", ".csv");
        byte[] expected = "timestamp,close\n1,42\n".getBytes();
        Files.write(file, expected);
        try {
            DatasetUploads.upload(HttpClient.newHttpClient(), session(url(server)), file);
            assertArrayEquals(expected, body.get());
            assertNull(authorization.get());
            assertNull(apiKey.get());
        } finally {
            Files.deleteIfExists(file);
            server.stop(0);
        }
    }

    @Test
    void acceptsInitialUploadFromDatasetCreation() throws Exception {
        AtomicReference<byte[]> body = new AtomicReference<>();
        HttpServer server = server(exchange -> {
            body.set(exchange.getRequestBody().readAllBytes());
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        Path file = Files.createTempFile("dataset-upload", ".csv");
        byte[] expected = "timestamp,close\n1,42\n".getBytes();
        Files.write(file, expected);
        DatasetCreated created = new DatasetCreated().upload(target(url(server)));

        try {
            DatasetUploads.upload(HttpClient.newHttpClient(), created, file);
            assertArrayEquals(expected, body.get());
        } finally {
            Files.deleteIfExists(file);
            server.stop(0);
        }
    }

    @Test
    void rejectsMissingFileBeforeNetwork() {
        QTSUploadError error = assertThrows(
                QTSUploadError.class,
                () -> DatasetUploads.upload(
                        HttpClient.newHttpClient(),
                        session("http://127.0.0.1:1/upload"),
                        Path.of("missing.csv")));
        assertNull(error.statusCode());
    }

    @Test
    void preservesHttpStatusWithoutLeakingTarget() throws Exception {
        HttpServer server = server(exchange -> {
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
        });
        Path file = Files.createTempFile("dataset-upload", ".csv");
        Files.writeString(file, "x");
        String target = url(server);
        try {
            QTSUploadError error = assertThrows(
                    QTSUploadError.class,
                    () -> DatasetUploads.upload(HttpClient.newHttpClient(), session(target), file));
            assertEquals(403, error.statusCode());
            assertFalse(error.getMessage().contains(target));
        } finally {
            Files.deleteIfExists(file);
            server.stop(0);
        }
    }

    @Test
    void wrapsTransportFailureWithoutLeakingTarget() throws Exception {
        Path file = Files.createTempFile("dataset-upload", ".csv");
        Files.writeString(file, "x");
        String target = "http://127.0.0.1:1/private-upload-token";
        try {
            QTSUploadError error = assertThrows(
                    QTSUploadError.class,
                    () -> DatasetUploads.upload(HttpClient.newHttpClient(), session(target), file));
            assertNull(error.statusCode());
            assertFalse(error.getMessage().contains(target));
            assertFalse(error.toString().contains(target));
            assertNull(error.getCause());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static HttpServer server(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/upload", handler);
        server.start();
        return server;
    }

    private static String url(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/upload";
    }

    private static DatasetUploadSession session(String url) {
        return new DatasetUploadSession().uploadId("up-1").upload(target(url));
    }

    private static DatasetUploadTarget target(String url) {
        return new DatasetUploadTarget().url(url).expiresInMinutes(1);
    }
}
