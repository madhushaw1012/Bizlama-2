package com.bizlama.api.explanations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bizlama.api.explanations.ExplanationPersistence.AuditEntry;
import com.bizlama.api.explanations.ExplanationPersistence.AuditOutcome;
import com.bizlama.api.explanations.ExplanationPersistence.CacheEntry;
import com.bizlama.api.explanations.ExplanationProvider.Metadata;
import com.bizlama.api.outbox.CanonicalJson;
import com.bizlama.api.recommendations.DemandCalculation;
import com.bizlama.api.recommendations.DemandCalculation.DemandScope;
import com.bizlama.api.recommendations.DemandCalculation.IngredientDemand;
import com.bizlama.api.recommendations.GovernedRecommendation;
import com.bizlama.api.recommendations.GovernedRecommendation.RiskTier;
import com.bizlama.api.recommendations.GovernedRecommendation.Status;
import com.bizlama.api.recommendations.GovernedRecommendation.Type;
import com.bizlama.api.recommendations.RecommendationService;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class RecommendationExplanationServiceTest {

    private static final Instant NOW =
            Instant.parse("2026-09-09T10:00:00Z");

    @Test
    void deterministicFallbackPreservesAuthoritativeQuantityWhenAiIsDisabled() {
        Fixture fixture = fixture(null);

        RecommendationExplanation result =
                fixture.service().explain(fixture.recommendation());

        assertThat(result.outcome())
                .isEqualTo(RecommendationExplanation.Outcome.FALLBACK);
        assertThat(result.authoritativeQuantity())
                .isEqualByComparingTo("2.500");
        assertThat(result.authoritativeUnit()).isEqualTo("kg");
        verify(fixture.persistence()).audit(any(AuditEntry.class));
    }

    @Test
    void boundedAiFailureIsAuditedBeforeDeterministicFallback() throws Exception {
        VertexGeminiExplanationProvider ai =
                mock(VertexGeminiExplanationProvider.class);
        Metadata metadata = new Metadata(
                "vertex-ai", "gemini-test", "prompt-v1");
        when(ai.metadata()).thenReturn(metadata);
        when(ai.explain(any())).thenThrow(
                new TimeoutException("contact chef@example.com at +1 555 111 2222"));
        Fixture fixture = fixture(ai);
        when(fixture.persistence().find(any())).thenReturn(Optional.empty());

        RecommendationExplanation result =
                fixture.service().explain(fixture.recommendation());

        assertThat(result.outcome())
                .isEqualTo(RecommendationExplanation.Outcome.FALLBACK);
        assertThat(result.authoritativeQuantity())
                .isEqualByComparingTo("2.500");
        ArgumentCaptor<AuditEntry> audits =
                ArgumentCaptor.forClass(AuditEntry.class);
        verify(fixture.persistence(),
                org.mockito.Mockito.times(2)).audit(audits.capture());
        assertThat(audits.getAllValues())
                .extracting(AuditEntry::outcome)
                .containsExactly(AuditOutcome.ERROR, AuditOutcome.FALLBACK);
        assertThat(audits.getAllValues().getFirst().errorMessage())
                .contains("[redacted-email]", "[redacted-phone]")
                .doesNotContain("chef@example.com", "555 111");
    }

    @Test
    void numericAiProseIsRejectedBeforeDeterministicFallback() throws Exception {
        VertexGeminiExplanationProvider ai =
                mock(VertexGeminiExplanationProvider.class);
        Metadata metadata = new Metadata(
                "vertex-ai", "gemini-test", "prompt-v1");
        when(ai.metadata()).thenReturn(metadata);
        when(ai.explain(any())).thenReturn(
                new RecommendationExplanation.Content(
                        "Purchase 9 kg now.",
                        List.of("The model restated a quantity."),
                        List.of()));
        Fixture fixture = fixture(ai);
        when(fixture.persistence().find(any())).thenReturn(Optional.empty());

        RecommendationExplanation result =
                fixture.service().explain(fixture.recommendation());

        assertThat(result.outcome())
                .isEqualTo(RecommendationExplanation.Outcome.FALLBACK);
        assertThat(result.content().summary()).contains("2.500 kg");
        ArgumentCaptor<AuditEntry> audits =
                ArgumentCaptor.forClass(AuditEntry.class);
        verify(fixture.persistence(),
                org.mockito.Mockito.times(2)).audit(audits.capture());
        assertThat(audits.getAllValues())
                .extracting(AuditEntry::outcome)
                .containsExactly(AuditOutcome.ERROR, AuditOutcome.FALLBACK);
    }

    @Test
    void validCacheHitSkipsAiAndKeepsPersistedGenerationTime() throws Exception {
        VertexGeminiExplanationProvider ai =
                mock(VertexGeminiExplanationProvider.class);
        Metadata metadata = new Metadata(
                "vertex-ai", "gemini-test", "prompt-v1");
        when(ai.metadata()).thenReturn(metadata);
        Fixture fixture = fixture(ai);
        Instant generatedAt = NOW.minusSeconds(30);
        when(fixture.persistence().find(any())).thenReturn(Optional.of(
                new CacheEntry(
                        ExplanationSupport.RESPONSE_SCHEMA_VERSION,
                        """
                                {
                                  "summary": "Cached explanation.",
                                  "drivers": ["Deterministic evidence."],
                                  "caveats": []
                                }
                                """,
                        generatedAt
                )
        ));

        RecommendationExplanation result =
                fixture.service().explain(fixture.recommendation());

        assertThat(result.outcome())
                .isEqualTo(RecommendationExplanation.Outcome.CACHE_HIT);
        assertThat(result.cached()).isTrue();
        assertThat(result.generatedAt()).isEqualTo(generatedAt);
        assertThat(result.authoritativeQuantity())
                .isEqualByComparingTo("2.500");
        verify(ai, never()).explain(any());
    }

    @SuppressWarnings("unchecked")
    private Fixture fixture(VertexGeminiExplanationProvider ai) {
        RecommendationService recommendations =
                mock(RecommendationService.class);
        ExplanationPersistence persistence =
                mock(ExplanationPersistence.class);
        ObjectProvider<VertexGeminiExplanationProvider> provider =
                mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(ai);
        GovernedRecommendation recommendation = recommendation();
        when(recommendations.calculation(recommendation.id()))
                .thenReturn(calculation());

        ExplanationProperties properties =
                new ExplanationProperties("prompt-v1", 12000);
        ExplanationRedactor redactor = new ExplanationRedactor();
        JsonMapper mapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .build();
        ExplanationSupport support =
                new ExplanationSupport(mapper, redactor);
        RecommendationExplanationService service =
                new RecommendationExplanationService(
                        recommendations,
                        properties,
                        support,
                        new CanonicalJson(mapper),
                        persistence,
                        new DeterministicExplanationProvider(
                                properties,
                                support
                        ),
                        provider,
                        redactor,
                        Clock.fixed(NOW, ZoneOffset.UTC)
                );
        return new Fixture(service, persistence, recommendation);
    }

    private GovernedRecommendation recommendation() {
        return new GovernedRecommendation(
                "recommendation-1",
                "kitchen-1",
                "location-1",
                Type.PURCHASE,
                "ingredient-1",
                null,
                null,
                new BigDecimal("2.500"),
                "kg",
                "calculation-1",
                new BigDecimal("0.9000"),
                "{}",
                "SHORTAGE",
                RiskTier.LOW,
                Status.PENDING,
                3,
                NOW.plusSeconds(3600),
                NOW.minusSeconds(60),
                "test",
                NOW.minusSeconds(30),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private DemandCalculation calculation() {
        IngredientDemand ingredient = new IngredientDemand(
                "ingredient-1",
                "Rice",
                "kg",
                new BigDecimal("10.000"),
                new BigDecimal("7.500"),
                new BigDecimal("7.500"),
                new BigDecimal("7.500"),
                BigDecimal.ZERO,
                "DEFAULT_ZERO",
                new BigDecimal("2.500"),
                new BigDecimal("2.500"),
                BigDecimal.ZERO,
                true,
                List.of("order-sensitive-id"),
                List.of("recipe-sensitive-id"),
                List.of(),
                List.of(),
                List.of()
        );
        return new DemandCalculation(
                new DemandScope(
                        "kitchen-1",
                        "location-1",
                        NOW.minusSeconds(60),
                        NOW.plusSeconds(3600),
                        NOW
                ),
                LocalDate.of(2026, 9, 9),
                LocalDate.of(2026, 9, 10),
                NOW,
                DemandCalculation.METHOD,
                List.of("ACCEPTED"),
                List.of(ingredient),
                List.of(),
                List.of()
        );
    }

    private record Fixture(
            RecommendationExplanationService service,
            ExplanationPersistence persistence,
            GovernedRecommendation recommendation
    ) {
    }
}
