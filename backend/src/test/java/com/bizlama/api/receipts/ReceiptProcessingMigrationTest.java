package com.bizlama.api.receipts;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class ReceiptProcessingMigrationTest {

    @Test
    void v15RecoversLegacyInterruptedAndFailedReceiptsForManualReview()
            throws Exception {
        String url = "jdbc:h2:mem:receipt-recovery-"
                + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .target("14")
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(insertLegacy(
                    "legacy-uploaded",
                    "UPLOADED",
                    null
            ));
            statement.executeUpdate(insertLegacy(
                    "legacy-extracting",
                    "EXTRACTING",
                    null
            ));
            statement.executeUpdate(insertLegacy(
                    "legacy-failed",
                    "FAILED",
                    "EXTRACTION_FAILED"
            ));
        }

        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            try (ResultSet receipts = statement.executeQuery("""
                    SELECT COUNT(*)
                    FROM receipt_imports
                    WHERE id LIKE 'legacy-%'
                      AND status = 'REVIEW_REQUIRED'
                      AND failure_code IS NOT NULL
                      AND version = 2
                    """)) {
                assertThat(singleCount(receipts)).isEqualTo(3);
            }
            try (ResultSet attempts = statement.executeQuery("""
                    SELECT COUNT(*)
                    FROM receipt_processing_attempts
                    WHERE receipt_id LIKE 'legacy-%'
                      AND attempt_trigger = 'LEGACY_RECOVERY'
                      AND outcome = 'MANUAL_REVIEW'
                      AND completed_at IS NOT NULL
                    """)) {
                assertThat(singleCount(attempts)).isEqualTo(3);
            }
            try (ResultSet failedStage = statement.executeQuery("""
                    SELECT failure_stage
                    FROM receipt_processing_attempts
                    WHERE receipt_id = 'legacy-failed'
                    """)) {
                assertThat(singleText(failedStage)).isEqualTo("EXTRACTION");
            }
            try (ResultSet interruptedStage = statement.executeQuery("""
                    SELECT failure_stage
                    FROM receipt_processing_attempts
                    WHERE receipt_id = 'legacy-extracting'
                    """)) {
                assertThat(singleText(interruptedStage)).isEqualTo("PERSISTENCE");
            }
        }
    }

    private String insertLegacy(
            String id,
            String status,
            String failureCode
    ) {
        String code = failureCode == null
                ? "NULL"
                : "'" + failureCode + "'";
        return """
                INSERT INTO receipt_imports
                (id, original_filename, object_uri, status, created_at,
                 kitchen_id, location_id, version, updated_at, failure_code)
                VALUES
                ('%s', '%s.pdf', 'private://%s', '%s', CURRENT_TIMESTAMP,
                 'kitchen-default', 'location-main', 1, CURRENT_TIMESTAMP, %s)
                """.formatted(id, id, id, status, code);
    }

    private long singleCount(ResultSet result) throws Exception {
        assertThat(result.next()).isTrue();
        return result.getLong(1);
    }

    private String singleText(ResultSet result) throws Exception {
        assertThat(result.next()).isTrue();
        return result.getString(1);
    }
}
