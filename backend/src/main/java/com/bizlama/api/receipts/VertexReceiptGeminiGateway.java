package com.bizlama.api.receipts;

import com.google.genai.Client;
import com.bizlama.api.ai.GeminiModelClient;
import com.bizlama.api.ai.GeminiModelClient.GcsDocumentInput;
import com.bizlama.api.ai.GeminiModelClient.Operation;
import com.google.genai.types.Content;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import com.google.genai.types.Part;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;


@Component("receiptGeminiModelClient")
@ConditionalOnProperty(
        prefix = "bizlama.receipts.ai",
        name = "enabled",
        havingValue = "true")
final class VertexReceiptGeminiGateway implements GeminiModelClient {

    private static final List<Integer> RETRYABLE_STATUS_CODES =
            List.of(408, 429, 500, 502, 503, 504);

    private final Client client;
    private final Semaphore permits;
    private final ReceiptAiProperties properties;

    VertexReceiptGeminiGateway(ReceiptAiProperties properties) {
        this(
                Client.builder()
                        .vertexAI(true)
                        .project(properties.projectId())
                        .location(properties.location())
                        .httpOptions(httpOptions(properties))
                        .build(),
                properties
        );
    }

    VertexReceiptGeminiGateway(
            Client client,
            ReceiptAiProperties properties
    ) {
        this.client = client;
        this.properties = properties;
        this.permits = new Semaphore(properties.maxConcurrent(), true);
    }

    @Override
    public String generate(Request request) {
        if (request.operation() != Operation.RECEIPT_EXTRACTION
                || !(request.input() instanceof GcsDocumentInput input)) {
            throw new IllegalArgumentException(
                    "Receipt client accepts only GCS receipt requests"
            );
        }
        boolean acquired;
        try {
            acquired = permits.tryAcquire(
                    properties.queueTimeout().toMillis(),
                    TimeUnit.MILLISECONDS
            );
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while waiting for receipt extraction capacity",
                    interrupted
            );
        }
        if (!acquired) {
            throw new RejectedExecutionException(
                    "Vertex receipt extraction concurrency limit is busy"
            );
        }

        try {
            Content content = Content.fromParts(
                    Part.fromText(input.prompt()),
                    Part.fromUri(input.uri(), input.mimeType())
            );
            return client.models
                    .generateContent(
                            request.model(),
                            content,
                            request.configuration()
                    )
                    .text();
        } finally {
            permits.release();
        }
    }

    static HttpOptions httpOptions(ReceiptAiProperties properties) {
        int timeoutMillis = Math.toIntExact(
                properties.timeout().toMillis());
        HttpRetryOptions retry = HttpRetryOptions.builder()
                .attempts(properties.maxAttempts())
                .initialDelay(0.5d)
                .maxDelay(2.0d)
                .expBase(2.0d)
                .jitter(0.2d)
                .httpStatusCodes(RETRYABLE_STATUS_CODES)
                .build();
        return HttpOptions.builder()
                .apiVersion("v1")
                .timeout(timeoutMillis)
                .retryOptions(retry)
                .build();
    }

    @PreDestroy
    void close() {
        client.close();
    }
}
