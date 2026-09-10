package com.bizlama.api.explanations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bizlama.api.ai.GeminiModelClient;
import com.bizlama.api.ai.GeminiModelClient.TextInput;
import com.bizlama.api.ai.GeminiRuntimeStatus;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class VertexGeminiExplanationProviderTest {

    @Test
    void appliesJsonSchemaAndTokenLimitThenValidatesResponse() throws Exception {
        CapturingGateway gateway = new CapturingGateway("""
                {
                  "summary": "Purchase the calculated quantity.",
                  "drivers": ["Demand exceeds usable supply."],
                  "caveats": ["The deterministic quantity remains authoritative."]
                }
                """);
        VertexGeminiExplanationProvider provider = provider(
                gateway,
                Duration.ofSeconds(1)
        );

        RecommendationExplanation.Content result =
                provider.explain(request());

        assertThat(result.summary())
                .isEqualTo("Purchase the calculated quantity.");
        assertThat(gateway.request.configuration().maxOutputTokens())
                .contains(256);
        assertThat(gateway.request.configuration().responseMimeType())
                .contains("application/json");
        assertThat(((TextInput) gateway.request.input()).prompt())
                .contains("Immutable redacted snapshot JSON")
                .contains("hash-1");
        provider.close();
    }

    @Test
    void invalidProviderSchemaIsRejected() {
        VertexGeminiExplanationProvider provider = provider(
                new CapturingGateway("""
                        {"summary":"Text","drivers":[],"caveats":[]}
                        """),
                Duration.ofSeconds(1)
        );

        assertThatThrownBy(() -> provider.explain(request()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("drivers");
        provider.close();
    }

    @Test
    void totalTimeoutBoundsSlowGateway() {
        GeminiModelClient slow = request -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return "{}";
        };
        VertexGeminiExplanationProvider provider = provider(
                slow,
                Duration.ofMillis(20)
        );

        assertThatThrownBy(() -> provider.explain(request()))
                .isInstanceOf(TimeoutException.class)
                .hasMessageContaining("total timeout");
        provider.close();
    }

    @Test
    void sdkConfigurationBoundsRetriesAndHttpTimeout() {
        VertexExplanationProperties properties = properties(
                Duration.ofMillis(750)
        );

        assertThat(VertexGeminiGateway.httpOptions(properties).apiVersion())
                .contains("v1");
        assertThat(VertexGeminiGateway.httpOptions(properties).timeout())
                .contains(750);
        assertThat(VertexGeminiGateway.httpOptions(properties)
                .retryOptions().orElseThrow().attempts()).contains(2);
        assertThat(VertexGeminiGateway.httpOptions(properties)
                .retryOptions().orElseThrow().httpStatusCodes().orElseThrow())
                .contains(429, 500, 502, 503, 504);
    }

    private VertexGeminiExplanationProvider provider(
            GeminiModelClient gateway,
            Duration timeout
    ) {
        ExplanationProperties explanation =
                new ExplanationProperties("prompt-v1", 12000);
        VertexExplanationProperties vertex = properties(timeout);
        ExplanationSupport support = new ExplanationSupport(
                JsonMapper.builder().build(),
                new ExplanationRedactor()
        );
        return new VertexGeminiExplanationProvider(
                explanation,
                vertex,
                support,
                gateway,
                new GeminiRuntimeStatus(),
                java.util.concurrent.Executors
                        .newVirtualThreadPerTaskExecutor()
        );
    }

    private VertexExplanationProperties properties(Duration timeout) {
        return new VertexExplanationProperties(
                true,
                "project-1",
                "global",
                "gemini-test",
                timeout,
                Duration.ofMillis(10),
                1,
                2,
                256
        );
    }

    private ExplanationProvider.Request request() {
        ExplanationProvider.Snapshot snapshot =
                new ExplanationProvider.Snapshot(
                        1,
                        "recommendation-1",
                        1,
                        "calculation-1",
                        "PURCHASE",
                        "PENDING",
                        "LOW",
                        "SHORTAGE",
                        new BigDecimal("2.5"),
                        "kg",
                        new BigDecimal("0.9"),
                        "rice",
                        "Rice",
                        "DETERMINISTIC_DEMAND_V1",
                        Instant.parse("2026-09-09T00:00:00Z"),
                        Instant.parse("2026-09-10T00:00:00Z"),
                        Instant.parse("2026-09-09T01:00:00Z"),
                        Map.of(
                                "grossDemand", new BigDecimal("10"),
                                "usableSupply", new BigDecimal("7.5"),
                                "shortage", new BigDecimal("2.5"))
                );
        return new ExplanationProvider.Request(
                snapshot,
                "{\"recommendationId\":\"recommendation-1\"}",
                "hash-1"
        );
    }

    private static final class CapturingGateway implements GeminiModelClient {
        private final String response;
        private GeminiModelClient.Request request;

        private CapturingGateway(String response) {
            this.response = response;
        }

        @Override
        public String generate(GeminiModelClient.Request request) {
            this.request = request;
            return response;
        }
    }
}
