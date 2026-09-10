package com.bizlama.api.explanations;

import com.bizlama.api.explanations.ExplanationPersistence.AuditEntry;
import com.bizlama.api.explanations.ExplanationPersistence.AuditOutcome;
import com.bizlama.api.explanations.ExplanationPersistence.CacheEntry;
import com.bizlama.api.explanations.ExplanationPersistence.CacheKey;
import com.bizlama.api.explanations.ExplanationProvider.Metadata;
import com.bizlama.api.explanations.ExplanationProvider.Request;
import com.bizlama.api.explanations.ExplanationProvider.Snapshot;
import com.bizlama.api.outbox.CanonicalJson;
import com.bizlama.api.recommendations.DemandCalculation;
import com.bizlama.api.recommendations.GovernedRecommendation;
import com.bizlama.api.recommendations.RecommendationService;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class RecommendationExplanationService {

    private final RecommendationService recommendations;
    private final ExplanationProperties properties;
    private final ExplanationSupport support;
    private final CanonicalJson canonicalJson;
    private final ExplanationPersistence persistence;
    private final DeterministicExplanationProvider fallback;
    private final ExplanationProvider aiProvider;
    private final ExplanationRedactor redactor;
    private final Clock clock;

    public RecommendationExplanationService(
            RecommendationService recommendations,
            ExplanationProperties properties,
            ExplanationSupport support,
            CanonicalJson canonicalJson,
            ExplanationPersistence persistence,
            DeterministicExplanationProvider fallback,
            ObjectProvider<VertexGeminiExplanationProvider> aiProvider,
            ExplanationRedactor redactor,
            Clock clock
    ) {
        this.recommendations = recommendations;
        this.properties = properties;
        this.support = support;
        this.canonicalJson = canonicalJson;
        this.persistence = persistence;
        this.fallback = fallback;
        this.aiProvider = aiProvider.getIfAvailable();
        this.redactor = redactor;
        this.clock = clock;
    }

    public RecommendationExplanation explain(
            GovernedRecommendation recommendation
    ) {
        DemandCalculation calculation = recommendations.calculation(
                recommendation.id());
        Snapshot snapshot = support.snapshot(recommendation, calculation);
        String canonicalInput = canonicalJson.write(snapshot);
        String inputHash = support.hash(
                properties.promptVersion(),
                canonicalInput
        );
        Request request = new Request(
                snapshot,
                canonicalInput,
                inputHash
        );

        if (aiProvider != null) {
            RecommendationExplanation cached = cached(
                    recommendation,
                    snapshot,
                    request,
                    aiProvider.metadata()
            );
            if (cached != null) {
                return cached;
            }

            long started = System.nanoTime();
            try {
                RecommendationExplanation.Content content =
                        support.validateGenerated(aiProvider.explain(request));
                long latency = elapsedMillis(started);
                Instant generatedAt = clock.instant();
                String responseJson = canonicalJson.write(content);
                CacheKey key = new CacheKey(
                        inputHash,
                        aiProvider.metadata()
                );
                persistence.cache(
                        key,
                        snapshot,
                        responseJson,
                        generatedAt
                );
                audit(
                        snapshot,
                        aiProvider.metadata(),
                        inputHash,
                        latency,
                        AuditOutcome.SUCCESS,
                        null,
                        null
                );
                return response(
                        recommendation,
                        content,
                        aiProvider.metadata(),
                        inputHash,
                        RecommendationExplanation.Outcome.GENERATED,
                        false,
                        generatedAt
                );
            } catch (Exception failure) {
                audit(
                        snapshot,
                        aiProvider.metadata(),
                        inputHash,
                        elapsedMillis(started),
                        AuditOutcome.ERROR,
                        failure.getClass().getSimpleName(),
                        redactor.error(failure)
                );
            }
        }

        return fallback(recommendation, snapshot, request);
    }

    private RecommendationExplanation cached(
            GovernedRecommendation recommendation,
            Snapshot snapshot,
            Request request,
            Metadata metadata
    ) {
        CacheKey key = new CacheKey(request.inputHash(), metadata);
        Optional<CacheEntry> cached = persistence.find(key);
        if (cached.isEmpty()) {
            return null;
        }

        long started = System.nanoTime();
        CacheEntry entry = cached.get();
        try {
            if (entry.responseSchemaVersion()
                    != ExplanationSupport.RESPONSE_SCHEMA_VERSION) {
                throw new IllegalArgumentException(
                        "Unsupported cached explanation schema"
                );
            }
            RecommendationExplanation.Content content =
                    support.validateGenerated(support.parse(entry.responseJson()));
            audit(
                    snapshot,
                    metadata,
                    request.inputHash(),
                    elapsedMillis(started),
                    AuditOutcome.CACHE_HIT,
                    null,
                    null
            );
            return response(
                    recommendation,
                    content,
                    metadata,
                    request.inputHash(),
                    RecommendationExplanation.Outcome.CACHE_HIT,
                    true,
                    entry.createdAt()
            );
        } catch (RuntimeException invalid) {
            persistence.invalidate(key);
            audit(
                    snapshot,
                    metadata,
                    request.inputHash(),
                    elapsedMillis(started),
                    AuditOutcome.ERROR,
                    "INVALID_CACHE_ENTRY",
                    redactor.error(invalid)
            );
            return null;
        }
    }

    private RecommendationExplanation fallback(
            GovernedRecommendation recommendation,
            Snapshot snapshot,
            Request request
    ) {
        long started = System.nanoTime();
        RecommendationExplanation.Content content;
        try {
            content = support.validate(fallback.explain(request));
        } catch (Exception impossible) {
            throw new IllegalStateException(
                    "Deterministic explanation fallback failed",
                    impossible
            );
        }
        Instant generatedAt = clock.instant();
        audit(
                snapshot,
                fallback.metadata(),
                request.inputHash(),
                elapsedMillis(started),
                AuditOutcome.FALLBACK,
                null,
                null
        );
        return response(
                recommendation,
                content,
                fallback.metadata(),
                request.inputHash(),
                RecommendationExplanation.Outcome.FALLBACK,
                false,
                generatedAt
        );
    }

    private RecommendationExplanation response(
            GovernedRecommendation recommendation,
            RecommendationExplanation.Content content,
            Metadata metadata,
            String inputHash,
            RecommendationExplanation.Outcome outcome,
            boolean cached,
            Instant generatedAt
    ) {
        return new RecommendationExplanation(
                recommendation.id(),
                recommendation.version(),
                recommendation.calculationId(),
                recommendation.proposedQuantity(),
                recommendation.unit(),
                content,
                metadata.provider(),
                metadata.model(),
                metadata.promptVersion(),
                inputHash,
                outcome,
                cached,
                generatedAt
        );
    }

    private void audit(
            Snapshot snapshot,
            Metadata metadata,
            String inputHash,
            long latencyMillis,
            AuditOutcome outcome,
            String errorCode,
            String errorMessage
    ) {
        persistence.audit(new AuditEntry(
                snapshot,
                metadata,
                inputHash,
                latencyMillis,
                outcome,
                errorCode,
                errorMessage,
                clock.instant()
        ));
    }

    private long elapsedMillis(long startedNanos) {
        return Math.max(
                0,
                TimeUnit.NANOSECONDS.toMillis(
                        System.nanoTime() - startedNanos)
        );
    }
}
