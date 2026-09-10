package com.bizlama.api.receipts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bizlama.api.domain.ReceiptImport;
import com.bizlama.api.stock.ExpiryProvenance;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.mock.web.MockMultipartFile;

@SpringBootTest
@ActiveProfiles("memory")
@Import(ReceiptWorkflowReliabilityIntegrationTest.TestDoubles.class)
class ReceiptWorkflowReliabilityIntegrationTest {

    @Autowired
    private ReceiptWorkflowService workflow;

    @Autowired
    private ReceiptReviewService reviews;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    @Qualifier("controlledReceiptFileStore")
    private ReceiptFileStore fileStore;

    @Autowired
    @Qualifier("controlledReceiptExtractor")
    private ReceiptExtractor extractor;

    @BeforeEach
    void setUp() {
        reset(fileStore, extractor);
        when(extractor.provider()).thenReturn("controlled-provider");
        when(extractor.model()).thenReturn("controlled-model");
        when(extractor.schemaVersion()).thenReturn("controlled-v1");
    }

    @AfterEach
    void cleanUp() {
        jdbc.sql("""
                        DELETE FROM analytics_outbox
                        WHERE entity_type = 'activity'
                          AND entity_id IN (
                            SELECT id
                            FROM activity_events
                            WHERE description LIKE 'Uploaded reliability-%')
                        """)
                .update();
        jdbc.sql("""
                        DELETE FROM activity_events
                        WHERE description LIKE 'Uploaded reliability-%'
                        """)
                .update();
        jdbc.sql("""
                        DELETE FROM receipt_imports
                        WHERE original_filename LIKE 'reliability-%'
                        """)
                .update();
    }

    @Test
    void providerOutageRetainsEvidenceAndOpensAuditedManualReview() {
        MockMultipartFile file = receiptFile();
        ReceiptFileStore.StoredReceipt stored = storedReceipt(file);
        when(fileStore.store(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.same(file)))
                .thenReturn(stored);
        when(extractor.extract(stored.uri(), file.getOriginalFilename(), file.getContentType()))
                .thenThrow(new IllegalStateException("provider unavailable"));

        ReceiptView result = workflow.upload(file);

        assertThat(result.id()).matches("RCT-[0-9A-F-]{36}");
        assertThat(result.status()).isEqualTo(ReceiptImport.Status.REVIEW_REQUIRED);
        assertThat(result.failureCode()).isEqualTo("EXTRACTION_FAILED");
        assertThat(result.failureMessage())
                .contains("private evidence")
                .contains("manual review");
        assertThat(result.objectUri()).isEqualTo(stored.uri());
        assertThat(result.items()).isEmpty();
        assertAttempt(result.id(), 1, "UPLOAD", "MANUAL_REVIEW", "EXTRACTION");
        verify(fileStore, never()).delete(stored);

        ReceiptView manual = workflow.addManualLine(
                result.id(),
                result.version(),
                new ReceiptWorkflowService.ManualLine(
                        "Operator entered item",
                        BigDecimal.ONE,
                        "g",
                        BigDecimal.TEN
                )
        );
        ReceiptView reviewed = reviews.review(
                manual.id(),
                new ReceiptReviewService.ReviewCommand(
                        manual.version(),
                        LocalDate.of(2026, 9, 9),
                        List.of(new ReceiptReviewService.LineReview(
                                manual.items().getFirst().id(),
                                true,
                                "paneer",
                                BigDecimal.ONE,
                                "g",
                                LocalDate.of(2026, 9, 12),
                                ExpiryProvenance.OWNER_CONFIRMED
                        ))
                ),
                "operator@example.test"
        );

        assertThat(reviewed.items()).hasSize(1);
        assertThat(reviewed.failureCode()).isNull();
        assertThat(reviewed.failureMessage()).isNull();
        assertAttempt(result.id(), 1, "UPLOAD", "MANUAL_REVIEW", "EXTRACTION");
    }

    @Test
    void malformedExtractionAndNormalizationFailuresDoNotStrandExtracting() {
        MockMultipartFile malformedFile = receiptFile();
        ReceiptFileStore.StoredReceipt malformedStored = storedReceipt(malformedFile);
        when(fileStore.store(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.same(malformedFile)
        )).thenReturn(malformedStored);
        when(extractor.extract(
                malformedStored.uri(),
                malformedFile.getOriginalFilename(),
                malformedFile.getContentType()
        )).thenReturn(null);

        ReceiptView malformed = workflow.upload(malformedFile);
        assertThat(malformed.status()).isEqualTo(ReceiptImport.Status.REVIEW_REQUIRED);
        assertThat(malformed.failureCode()).isEqualTo("EXTRACTION_FAILED");
        assertAttempt(
                malformed.id(),
                1,
                "UPLOAD",
                "MANUAL_REVIEW",
                "EXTRACTION"
        );

        MockMultipartFile invalidLineFile = receiptFile();
        ReceiptFileStore.StoredReceipt invalidLineStored =
                storedReceipt(invalidLineFile);
        when(fileStore.store(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.same(invalidLineFile)
        )).thenReturn(invalidLineStored);
        when(extractor.extract(
                invalidLineStored.uri(),
                invalidLineFile.getOriginalFilename(),
                invalidLineFile.getContentType()
        )).thenReturn(new ReceiptExtractor.Extraction(
                "Test merchant",
                LocalDate.of(2026, 9, 9),
                BigDecimal.TEN,
                List.of(new ReceiptExtractor.Line(
                        "Invalid quantity",
                        BigDecimal.ZERO,
                        "g",
                        BigDecimal.ONE,
                        BigDecimal.ONE
                ))
        ));

        ReceiptView invalidLine = workflow.upload(invalidLineFile);
        assertThat(invalidLine.status())
                .isEqualTo(ReceiptImport.Status.REVIEW_REQUIRED);
        assertThat(invalidLine.failureCode()).isEqualTo("NORMALIZATION_FAILED");
        assertAttempt(
                invalidLine.id(),
                1,
                "UPLOAD",
                "MANUAL_REVIEW",
                "NORMALIZATION"
        );
    }

    @Test
    void linePersistenceFailureRollsBackLinesAndTransitionsDurably() {
        MockMultipartFile file = receiptFile();
        ReceiptFileStore.StoredReceipt stored = storedReceipt(file);
        when(fileStore.store(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.same(file)
        )).thenReturn(stored);
        when(extractor.extract(
                stored.uri(),
                file.getOriginalFilename(),
                file.getContentType()
        )).thenReturn(new ReceiptExtractor.Extraction(
                "Test merchant",
                LocalDate.of(2026, 9, 9),
                BigDecimal.TEN,
                List.of(new ReceiptExtractor.Line(
                        "x".repeat(501),
                        BigDecimal.ONE,
                        "g",
                        BigDecimal.ONE,
                        BigDecimal.ONE
                ))
        ));

        ReceiptView result = workflow.upload(file);

        assertThat(result.status()).isEqualTo(ReceiptImport.Status.REVIEW_REQUIRED);
        assertThat(result.failureCode()).isEqualTo("PERSISTENCE_FAILED");
        assertThat(result.items()).isEmpty();
        assertThat(countItems(result.id())).isZero();
        assertAttempt(result.id(), 1, "UPLOAD", "MANUAL_REVIEW", "PERSISTENCE");
    }

    @Test
    void failedExtractionCanBeRetriedAgainstTheSavedEvidence() {
        MockMultipartFile file = receiptFile();
        ReceiptFileStore.StoredReceipt stored = storedReceipt(file);
        when(fileStore.store(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.same(file)
        )).thenReturn(stored);
        when(extractor.extract(
                stored.uri(),
                file.getOriginalFilename(),
                file.getContentType()
        )).thenThrow(new IllegalStateException("temporary outage"));

        ReceiptView failed = workflow.upload(file);

        doReturn(validExtraction()).when(extractor).extract(
                stored.uri(),
                file.getOriginalFilename(),
                file.getContentType()
        );

        ReceiptView retried = workflow.retryExtraction(
                failed.id(),
                failed.version()
        );

        assertThat(retried.status()).isEqualTo(ReceiptImport.Status.REVIEW_REQUIRED);
        assertThat(retried.failureCode()).isNull();
        assertThat(retried.failureMessage()).isNull();
        assertThat(retried.items()).hasSize(1);
        assertAttempt(
                failed.id(),
                1,
                "UPLOAD",
                "MANUAL_REVIEW",
                "EXTRACTION"
        );
        assertAttempt(failed.id(), 2, "RETRY", "SUCCEEDED", null);
    }

    @Test
    void metadataInsertFailureDeletesPreviouslyStoredEvidence() {
        MockMultipartFile file = receiptFile();
        ReceiptFileStore.StoredReceipt stored =
                new ReceiptFileStore.StoredReceipt(
                        "private://" + "x".repeat(1001),
                        "application/pdf",
                        "oversized-metadata",
                        null
                );
        when(fileStore.store(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.same(file)
        )).thenReturn(stored);

        assertThatThrownBy(() -> workflow.upload(file))
                .isInstanceOf(RuntimeException.class);

        verify(fileStore).delete(stored);
        assertThat(jdbc.sql("""
                        SELECT COUNT(*)
                        FROM receipt_imports
                        WHERE original_filename = :filename
                        """)
                .param("filename", file.getOriginalFilename())
                .query(Long.class)
                .single()).isZero();
    }

    private void assertAttempt(
            String receiptId,
            int attemptNumber,
            String trigger,
            String outcome,
            String stage
    ) {
        Attempt attempt = jdbc.sql("""
                        SELECT attempt_trigger, outcome, failure_stage,
                               failure_code, completed_at
                        FROM receipt_processing_attempts
                        WHERE receipt_id = :receipt
                          AND attempt_number = :attempt
                        """)
                .param("receipt", receiptId)
                .param("attempt", attemptNumber)
                .query((rs, row) -> new Attempt(
                        rs.getString("attempt_trigger"),
                        rs.getString("outcome"),
                        rs.getString("failure_stage"),
                        rs.getString("failure_code"),
                        rs.getObject("completed_at") != null
                ))
                .single();

        assertThat(attempt.trigger()).isEqualTo(trigger);
        assertThat(attempt.outcome()).isEqualTo(outcome);
        assertThat(attempt.stage()).isEqualTo(stage);
        assertThat(attempt.completed()).isTrue();
        if ("MANUAL_REVIEW".equals(outcome)) {
            assertThat(attempt.failureCode()).isNotBlank();
        } else {
            assertThat(attempt.failureCode()).isNull();
        }
    }

    private long countItems(String receiptId) {
        return jdbc.sql("""
                        SELECT COUNT(*)
                        FROM receipt_items
                        WHERE receipt_id = :receipt
                        """)
                .param("receipt", receiptId)
                .query(Long.class)
                .single();
    }

    private MockMultipartFile receiptFile() {
        String name = "reliability-" + UUID.randomUUID() + ".pdf";
        return new MockMultipartFile(
                "file",
                name,
                "application/pdf",
                "%PDF-1.4\nprivate-test-evidence".getBytes(
                        StandardCharsets.UTF_8
                )
        );
    }

    private ReceiptFileStore.StoredReceipt storedReceipt(
            MockMultipartFile file
    ) {
        String key = "receipts/test/" + file.getOriginalFilename();
        return new ReceiptFileStore.StoredReceipt(
                "private://" + key,
                "application/pdf",
                key,
                null
        );
    }

    private ReceiptExtractor.Extraction validExtraction() {
        return new ReceiptExtractor.Extraction(
                "Test merchant",
                LocalDate.of(2026, 9, 9),
                BigDecimal.TEN,
                List.of(new ReceiptExtractor.Line(
                        "Unmatched test item",
                        BigDecimal.ONE,
                        "g",
                        BigDecimal.ONE,
                        BigDecimal.ONE
                ))
        );
    }

    private record Attempt(
            String trigger,
            String outcome,
            String stage,
            String failureCode,
            boolean completed
    ) {
    }

    @TestConfiguration
    static class TestDoubles {

        @Bean
        @Primary
        ReceiptFileStore controlledReceiptFileStore() {
            return mock(ReceiptFileStore.class);
        }

        @Bean
        @Primary
        ReceiptExtractor controlledReceiptExtractor() {
            return mock(ReceiptExtractor.class);
        }
    }
}
