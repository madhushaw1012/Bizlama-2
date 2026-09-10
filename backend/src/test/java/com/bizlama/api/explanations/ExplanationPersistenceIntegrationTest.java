package com.bizlama.api.explanations;

import static org.assertj.core.api.Assertions.assertThat;

import com.bizlama.api.config.JdbcTimestamp;
import com.bizlama.api.explanations.ExplanationPersistence.AuditEntry;
import com.bizlama.api.explanations.ExplanationPersistence.AuditOutcome;
import com.bizlama.api.explanations.ExplanationPersistence.CacheKey;
import com.bizlama.api.explanations.ExplanationProvider.Metadata;
import com.bizlama.api.explanations.ExplanationProvider.Snapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("memory")
class ExplanationPersistenceIntegrationTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ExplanationPersistence persistence;

    private String recommendationId;
    private String calculationId;
    private String ingredientId;

    @BeforeEach
    void setUp() {
        recommendationId = "explanation-rec-" + UUID.randomUUID();
        calculationId = "explanation-calc-" + UUID.randomUUID();
        ingredientId = jdbc.sql("""
                        SELECT id
                        FROM ingredients
                        WHERE kitchen_id = 'kitchen-default'
                          AND active = TRUE
                        ORDER BY id
                        LIMIT 1
                        """)
                .query(String.class)
                .single();

        Instant now = Instant.parse("2026-09-09T10:00:00Z");
        jdbc.sql("""
                        INSERT INTO demand_calculation_snapshots
                        (id, kitchen_id, location_id, horizon_start, horizon_end,
                         as_of, schema_version, calculation_method, payload_json,
                         payload_sha256, calculated_at, created_by)
                        VALUES
                        (:id, 'kitchen-default', 'location-main', :start, :end,
                         :asOf, 1, 'DETERMINISTIC_DEMAND_V1', '{}',
                         :hash, :calculatedAt, 'test')
                        """)
                .param("id", calculationId)
                .param("start", JdbcTimestamp.utc(now.minusSeconds(60)))
                .param("end", JdbcTimestamp.utc(now.plusSeconds(3600)))
                .param("asOf", JdbcTimestamp.utc(now))
                .param("hash", "0".repeat(64))
                .param("calculatedAt", JdbcTimestamp.utc(now))
                .update();
        jdbc.sql("""
                        INSERT INTO recommendations
                        (id, kitchen_id, location_id, recommendation_type,
                         ingredient_id, proposed_quantity, unit, calculation_id,
                         calculation_schema_version, confidence,
                         confidence_components_json, reason_code, risk_tier,
                         status, version, expires_at, created_at, created_by,
                         updated_at)
                        SELECT
                         :id, 'kitchen-default', 'location-main', 'PURCHASE',
                         ingredient.id, 2.500, ingredient.base_unit, :calculation,
                         1, 0.9000, '{}', 'SHORTAGE', 'LOW',
                         'PENDING', 1, :expiresAt, :createdAt, 'test', :updatedAt
                        FROM ingredients ingredient
                        WHERE ingredient.id = :ingredient
                        """)
                .param("id", recommendationId)
                .param("calculation", calculationId)
                .param("expiresAt", JdbcTimestamp.utc(now.plusSeconds(1800)))
                .param("createdAt", JdbcTimestamp.utc(now))
                .param("updatedAt", JdbcTimestamp.utc(now))
                .param("ingredient", ingredientId)
                .update();
    }

    @AfterEach
    void tearDown() {
        jdbc.sql("""
                        DELETE FROM recommendation_explanation_audit
                        WHERE recommendation_id = :id
                        """)
                .param("id", recommendationId)
                .update();
        jdbc.sql("""
                        DELETE FROM recommendation_explanation_cache
                        WHERE recommendation_id = :id
                        """)
                .param("id", recommendationId)
                .update();
        jdbc.sql("DELETE FROM recommendations WHERE id = :id")
                .param("id", recommendationId)
                .update();
        jdbc.sql("DELETE FROM demand_calculation_snapshots WHERE id = :id")
                .param("id", calculationId)
                .update();
    }

    @Test
    void cacheIsIdempotentAndEveryOutcomeIsAuditable() {
        Metadata metadata = new Metadata(
                "vertex-ai",
                "gemini-test",
                "prompt-v1"
        );
        CacheKey key = new CacheKey("a".repeat(64), metadata);
        Snapshot snapshot = snapshot();
        Instant createdAt = Instant.parse("2026-09-09T10:00:01Z");

        persistence.cache(
                key,
                snapshot,
                "{\"summary\":\"Text\",\"drivers\":[\"One\"],\"caveats\":[]}",
                createdAt
        );
        persistence.cache(
                key,
                snapshot,
                "{\"summary\":\"Different\",\"drivers\":[\"Two\"],\"caveats\":[]}",
                createdAt.plusSeconds(1)
        );
        persistence.audit(new AuditEntry(
                snapshot,
                metadata,
                key.inputHash(),
                12,
                AuditOutcome.SUCCESS,
                null,
                null,
                createdAt
        ));
        persistence.audit(new AuditEntry(
                snapshot,
                metadata,
                key.inputHash(),
                15,
                AuditOutcome.ERROR,
                "TimeoutException",
                "bounded timeout",
                createdAt.plusSeconds(1)
        ));

        assertThat(persistence.find(key)).get().satisfies(entry -> {
            assertThat(entry.responseJson()).contains("\"summary\":\"Text\"");
            assertThat(entry.createdAt()).isEqualTo(createdAt);
        });
        assertThat(jdbc.sql("""
                        SELECT COUNT(*)
                        FROM recommendation_explanation_cache
                        WHERE recommendation_id = :id
                        """)
                .param("id", recommendationId)
                .query(Long.class)
                .single()).isEqualTo(1L);
        assertThat(jdbc.sql("""
                        SELECT outcome
                        FROM recommendation_explanation_audit
                        WHERE recommendation_id = :id
                        ORDER BY created_at
                        """)
                .param("id", recommendationId)
                .query(String.class)
                .list()).containsExactly("SUCCESS", "ERROR");
    }

    private Snapshot snapshot() {
        return new Snapshot(
                1,
                recommendationId,
                1,
                calculationId,
                "PURCHASE",
                "PENDING",
                "LOW",
                "SHORTAGE",
                new BigDecimal("2.5"),
                "g",
                new BigDecimal("0.9"),
                ingredientId,
                "Ingredient",
                "DETERMINISTIC_DEMAND_V1",
                Instant.parse("2026-09-09T09:59:00Z"),
                Instant.parse("2026-09-09T11:00:00Z"),
                Instant.parse("2026-09-09T10:00:00Z"),
                Map.of("shortage", new BigDecimal("2.5"))
        );
    }
}
