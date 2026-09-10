package com.bizlama.api.explanations;

import com.bizlama.api.config.JdbcTimestamp;
import com.bizlama.api.explanations.ExplanationProvider.Metadata;
import com.bizlama.api.explanations.ExplanationProvider.Snapshot;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ExplanationPersistence {

    private final JdbcClient jdbc;
    private final boolean postgres;

    public ExplanationPersistence(JdbcClient jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.postgres = isPostgres(dataSource);
    }

    @Transactional(readOnly = true)
    public Optional<CacheEntry> find(CacheKey key) {
        return jdbc.sql("""
                        SELECT response_schema_version, response_json, created_at
                        FROM recommendation_explanation_cache
                        WHERE input_hash = :hash
                          AND prompt_version = :prompt
                          AND provider = :provider
                          AND model = :model
                        """)
                .param("hash", key.inputHash())
                .param("prompt", key.metadata().promptVersion())
                .param("provider", key.metadata().provider())
                .param("model", key.metadata().model())
                .query(ExplanationPersistence::cacheEntry)
                .optional();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cache(
            CacheKey key,
            Snapshot snapshot,
            String responseJson,
            Instant createdAt
    ) {
        JdbcClient.StatementSpec statement = jdbc.sql(postgres ? """
                        INSERT INTO recommendation_explanation_cache
                        (input_hash, prompt_version, provider, model,
                         recommendation_id, recommendation_version,
                         calculation_id, response_schema_version,
                         response_json, created_at)
                        VALUES
                        (:hash, :prompt, :provider, :model,
                         :recommendation, :version,
                         :calculation, :schema,
                         :response, :createdAt)
                        ON CONFLICT
                        (input_hash, prompt_version, provider, model)
                        DO NOTHING
                        """ : """
                        INSERT INTO recommendation_explanation_cache
                        (input_hash, prompt_version, provider, model,
                         recommendation_id, recommendation_version,
                         calculation_id, response_schema_version,
                         response_json, created_at)
                        SELECT
                         :hash, :prompt, :provider, :model,
                         :recommendation, :version,
                         :calculation, :schema,
                         :response, :createdAt
                        WHERE NOT EXISTS (
                          SELECT 1
                          FROM recommendation_explanation_cache
                          WHERE input_hash = :hash
                            AND prompt_version = :prompt
                            AND provider = :provider
                            AND model = :model
                        )
                        """)
                .param("hash", key.inputHash())
                .param("prompt", key.metadata().promptVersion())
                .param("provider", key.metadata().provider())
                .param("model", key.metadata().model())
                .param("recommendation", snapshot.recommendationId())
                .param("version", snapshot.recommendationVersion())
                .param("calculation", snapshot.calculationId())
                .param("schema", ExplanationSupport.RESPONSE_SCHEMA_VERSION)
                .param("response", responseJson)
                .param("createdAt", JdbcTimestamp.utc(createdAt));
        statement.update();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void invalidate(CacheKey key) {
        jdbc.sql("""
                        DELETE FROM recommendation_explanation_cache
                        WHERE input_hash = :hash
                          AND prompt_version = :prompt
                          AND provider = :provider
                          AND model = :model
                        """)
                .param("hash", key.inputHash())
                .param("prompt", key.metadata().promptVersion())
                .param("provider", key.metadata().provider())
                .param("model", key.metadata().model())
                .update();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void audit(AuditEntry entry) {
        jdbc.sql("""
                        INSERT INTO recommendation_explanation_audit
                        (id, recommendation_id, recommendation_version,
                         calculation_id, provider, model, prompt_version,
                         input_hash, response_schema_version, latency_ms,
                         outcome, error_code, error_message, created_at)
                        VALUES
                        (:id, :recommendation, :version,
                         :calculation, :provider, :model, :prompt,
                         :hash, :schema, :latency,
                         :outcome, :errorCode, :errorMessage, :createdAt)
                        """)
                .param("id", UUID.randomUUID().toString())
                .param("recommendation", entry.snapshot().recommendationId())
                .param("version", entry.snapshot().recommendationVersion())
                .param("calculation", entry.snapshot().calculationId())
                .param("provider", entry.metadata().provider())
                .param("model", entry.metadata().model())
                .param("prompt", entry.metadata().promptVersion())
                .param("hash", entry.inputHash())
                .param("schema", ExplanationSupport.RESPONSE_SCHEMA_VERSION)
                .param("latency", entry.latencyMillis())
                .param("outcome", entry.outcome().name())
                .param("errorCode", entry.errorCode())
                .param("errorMessage", entry.errorMessage())
                .param("createdAt", JdbcTimestamp.utc(entry.createdAt()))
                .update();
    }

    private static CacheEntry cacheEntry(ResultSet result, int row)
            throws SQLException {
        OffsetDateTime createdAt = result.getObject(
                "created_at",
                OffsetDateTime.class
        );
        return new CacheEntry(
                result.getInt("response_schema_version"),
                result.getString("response_json"),
                createdAt.toInstant()
        );
    }

    private static boolean isPostgres(DataSource dataSource) {
        try (var connection = dataSource.getConnection()) {
            return "PostgreSQL".equalsIgnoreCase(
                    connection.getMetaData().getDatabaseProductName()
            );
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Cannot identify the explanation cache database",
                    exception
            );
        }
    }

    public enum AuditOutcome {
        SUCCESS,
        CACHE_HIT,
        FALLBACK,
        ERROR
    }

    public record CacheKey(
            String inputHash,
            Metadata metadata
    ) {
    }

    public record CacheEntry(
            int responseSchemaVersion,
            String responseJson,
            Instant createdAt
    ) {
    }

    public record AuditEntry(
            Snapshot snapshot,
            Metadata metadata,
            String inputHash,
            long latencyMillis,
            AuditOutcome outcome,
            String errorCode,
            String errorMessage,
            Instant createdAt
    ) {
    }
}
