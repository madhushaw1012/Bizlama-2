package com.bizlama.api.explanations;

import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import jakarta.annotation.PreDestroy;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

interface GeminiGateway {
    String generate(
            String model,
            String prompt,
            GenerateContentConfig configuration
    );
}

@Component
@ConditionalOnProperty(
        prefix = "bizlama.explanations.vertex",
        name = "enabled",
        havingValue = "true")
final class VertexGeminiGateway implements GeminiGateway {

    private static final List<Integer> RETRYABLE_STATUS_CODES =
            List.of(429, 500, 502, 503, 504);

    private final Client client;

    VertexGeminiGateway(VertexExplanationProperties properties) {
        this.client = Client.builder()
                .vertexAI(true)
                .project(properties.projectId().trim())
                .location(properties.location().trim())
                .httpOptions(httpOptions(properties))
                .build();
    }

    @Override
    public String generate(
            String model,
            String prompt,
            GenerateContentConfig configuration
    ) {
        return client.models
                .generateContent(model, prompt, configuration)
                .text();
    }

    static HttpOptions httpOptions(VertexExplanationProperties properties) {
        int timeoutMillis = Math.toIntExact(
                properties.timeout().toMillis());
        HttpRetryOptions retry = HttpRetryOptions.builder()
                .attempts(properties.maxAttempts())
                .initialDelay(0.25d)
                .maxDelay(1.0d)
                .expBase(2.0d)
                .jitter(0.2d)
                .httpStatusCodes(RETRYABLE_STATUS_CODES)
                .build();
        return HttpOptions.builder()
                .timeout(timeoutMillis)
                .retryOptions(retry)
                .build();
    }

    @PreDestroy
    void close() {
        client.close();
    }
}
