package com.qtsurfer.api.sdk.internal;

import com.qtsurfer.api.client.model.DatasetCreated;
import com.qtsurfer.api.client.model.DatasetUploadSession;
import com.qtsurfer.api.client.model.DatasetUploadTarget;
import com.qtsurfer.api.sdk.errors.QTSUploadError;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Transfers dataset bytes to presigned upload targets without an API-auth interceptor.
 *
 * @since 0.18.0
 */
public final class DatasetUploads {

    private DatasetUploads() {
    }

    /**
     * Upload a file using the initial target returned when a dataset is created.
     *
     * @param client HTTP client used for the transfer
     * @param created newly created dataset containing its first upload target
     * @param file readable regular file to transfer
     * @throws QTSUploadError when the input is invalid, the transfer fails, or the target rejects it
     */
    public static void upload(HttpClient client, DatasetCreated created, Path file) {
        if (created == null) {
            throw new QTSUploadError("Dataset creation result has no upload target");
        }
        upload(client, created.getUpload(), file);
    }

    /**
     * Upload a file using a target opened for an existing dataset.
     *
     * @param client HTTP client used for the transfer
     * @param session upload session containing the presigned target
     * @param file readable regular file to transfer
     * @throws QTSUploadError when the input is invalid, the transfer fails, or the target rejects it
     */
    public static void upload(HttpClient client, DatasetUploadSession session, Path file) {
        if (session == null) {
            throw new QTSUploadError("Dataset upload session has no target");
        }
        upload(client, session.getUpload(), file);
    }

    private static void upload(HttpClient client, DatasetUploadTarget target, Path file) {
        if (client == null) {
            throw new QTSUploadError("Dataset upload HTTP client is required");
        }
        if (target == null || target.getUrl() == null || target.getUrl().isBlank()) {
            throw new QTSUploadError("Dataset upload session has no target");
        }
        if (file == null || !Files.isRegularFile(file) || !Files.isReadable(file)) {
            throw new QTSUploadError("Dataset upload file is not a readable regular file");
        }

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(target.getUrl()))
                    .PUT(HttpRequest.BodyPublishers.ofFile(file))
                    .build();
            HttpResponse<Void> response = client.send(
                    request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 != 2) {
                throw new QTSUploadError(
                        "Dataset upload rejected: HTTP " + response.statusCode(),
                        response.statusCode());
            }
        } catch (IOException | IllegalArgumentException e) {
            // These exceptions can embed the presigned target. Do not expose them as a cause.
            throw new QTSUploadError("Dataset upload transport failed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new QTSUploadError("Dataset upload interrupted");
        }
    }
}
