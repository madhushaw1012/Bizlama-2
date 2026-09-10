package com.bizlama.api.receipts;

import com.bizlama.api.catalog.ProductNormalizer;
import com.bizlama.api.config.JdbcTimestamp;
import com.bizlama.api.config.WorkspaceProperties;
import com.bizlama.api.domain.Ingredient;
import com.bizlama.api.domain.ReceiptImport;
import com.bizlama.api.quantity.CanonicalQuantity;
import com.bizlama.api.quantity.IncompatibleUnitException;
import com.bizlama.api.quantity.UnitConversionService;
import com.bizlama.api.store.OperationalRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReceiptWorkflowService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(ReceiptWorkflowService.class);

    private final ReceiptEvidenceValidator evidenceValidator;
    private final ReceiptFileStore fileStore;
    private final ReceiptExtractor extractor;
    private final ProductNormalizer normalizer;
    private final UnitConversionService units;
    private final OperationalRepository repository;
    private final ReceiptQueryService queries;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final WorkspaceProperties workspace;

    public ReceiptWorkflowService(
            ReceiptEvidenceValidator evidenceValidator,
            ReceiptFileStore fileStore,
            ReceiptExtractor extractor,
            ProductNormalizer normalizer,
            UnitConversionService units,
            OperationalRepository repository,
            ReceiptQueryService queries,
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            WorkspaceProperties workspace
    ) {
        this.evidenceValidator = evidenceValidator;
        this.fileStore = fileStore;
        this.extractor = extractor;
        this.normalizer = normalizer;
        this.units = units;
        this.repository = repository;
        this.queries = queries;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.workspace = workspace;
    }

    public ReceiptView upload(MultipartFile file) {
        ReceiptEvidenceValidator.Evidence evidence =
                evidenceValidator.validate(file);
        String id = receiptId();
        ReceiptFileStore.StoredReceipt stored = fileStore.store(id, file);
        Instant now = Instant.now();

        try {
            transactions.executeWithoutResult(status -> {
                scopedSql("""
                                INSERT INTO receipt_imports
                                (id, original_filename, object_uri, status,
                                 created_at, kitchen_id, location_id, version,
                                 updated_at, evidence_content_type,
                                 evidence_size_bytes, evidence_sha256,
                                 evidence_validated_at)
                                VALUES
                                (:id, :filename, :uri, 'UPLOADED',
                                 :now, :workspaceKitchen, :workspaceLocation, 1,
                                 :now, :contentType, :sizeBytes, :sha256, :now)
                                """)
                        .param("id", id)
                        .param("filename", evidence.filename())
                        .param("uri", stored.uri())
                        .param("now", JdbcTimestamp.utc(now))
                        .param("contentType", evidence.contentType())
                        .param("sizeBytes", evidence.sizeBytes())
                        .param("sha256", evidence.sha256())
                        .update();
                transitionToExtracting(id);
                beginAttempt(id, 1, "UPLOAD", now);
            });
        } catch (RuntimeException persistenceFailure) {
            compensateStoredEvidence(id, stored, persistenceFailure);
            throw persistenceFailure;
        }

        return process(
                id,
                new ReceiptEvidence(
                        stored.uri(),
                        evidence.filename(),
                        evidence.contentType()
                )
        );
    }

    public ReceiptView retryExtraction(
            String receiptId,
            int expectedVersion
    ) {
        ReceiptEvidence evidence = transactions.execute(status -> {
            RetryableReceipt receipt = scopedSql("""
                            SELECT id, object_uri, original_filename,
                                   evidence_content_type, status, version,
                                   failure_code
                            FROM receipt_imports
                            WHERE id = :id
                              AND kitchen_id = :workspaceKitchen
                              AND location_id = :workspaceLocation
                            FOR UPDATE
                            """)
                    .param("id", receiptId)
                    .query((rs, row) -> new RetryableReceipt(
                            rs.getString("id"),
                            rs.getString("object_uri"),
                            rs.getString("original_filename"),
                            rs.getString("evidence_content_type"),
                            ReceiptImport.Status.valueOf(
                                    rs.getString("status")
                            ),
                            rs.getInt("version"),
                            rs.getString("failure_code")
                    ))
                    .optional()
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            "Receipt not found."
                    ));

            if (receipt.status() != ReceiptImport.Status.REVIEW_REQUIRED
                    || receipt.failureCode() == null) {
                throw conflict(
                        "Receipt does not have a failed extraction to retry."
                );
            }
            if (receipt.version() != expectedVersion) {
                throw conflict(
                        "Receipt changed; reload before retrying extraction."
                );
            }
            long lines = scopedSql("""
                            SELECT COUNT(*)
                            FROM receipt_items item
                            WHERE item.receipt_id = :id
                              AND EXISTS (
                                SELECT 1
                                FROM receipt_imports receipt
                                WHERE receipt.id = item.receipt_id
                                  AND receipt.kitchen_id = :workspaceKitchen
                                  AND receipt.location_id = :workspaceLocation)
                            """)
                    .param("id", receiptId)
                    .query(Long.class)
                    .single();
            if (lines > 0) {
                throw conflict(
                        "Extraction cannot be retried after receipt lines exist."
                );
            }

            int changed = scopedSql("""
                            UPDATE receipt_imports
                            SET status = 'EXTRACTING',
                                updated_at = :now,
                                version = version + 1
                            WHERE id = :id
                              AND kitchen_id = :workspaceKitchen
                              AND location_id = :workspaceLocation
                              AND status = 'REVIEW_REQUIRED'
                              AND version = :version
                            """)
                    .param("now", JdbcTimestamp.utc(Instant.now()))
                    .param("id", receiptId)
                    .param("version", expectedVersion)
                    .update();
            if (changed != 1) {
                throw conflict(
                        "Receipt changed; reload before retrying extraction."
                );
            }

            int attemptNumber = scopedSql("""
                            SELECT COALESCE(MAX(attempt_number), 0) + 1
                            FROM receipt_processing_attempts
                            WHERE receipt_id = :id
                              AND kitchen_id = :workspaceKitchen
                              AND location_id = :workspaceLocation
                            """)
                    .param("id", receiptId)
                    .query(Integer.class)
                    .single();
            beginAttempt(
                    receiptId,
                    attemptNumber,
                    "RETRY",
                    Instant.now()
            );
            return new ReceiptEvidence(
                    receipt.objectUri(),
                    receipt.originalFilename(),
                    receipt.contentType() == null
                            ? "application/octet-stream"
                            : receipt.contentType()
            );
        });

        return process(receiptId, evidence);
    }

    public ReceiptView addManualLine(
            String receiptId,
            int expectedVersion,
            ManualLine command
    ) {
        if (command.quantity() == null || command.quantity().signum() <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Line quantity must be positive."
            );
        }
        requireText(command.rawName(), "Printed name");
        requireText(command.unit(), "Line unit");
        units.canonicalize(command.quantity(), command.unit());

        return transactions.execute(status -> {
            ReceiptState receipt = lock(receiptId);
            requireReviewable(receipt, expectedVersion);

            String lineId = UUID.randomUUID().toString();
            int inserted = scopedSql("""
                            INSERT INTO receipt_items
                            (id, receipt_id, raw_name, ingredient_id,
                             canonical_name, quantity, unit, unit_price,
                             confidence, selected, source_quantity, source_unit,
                             expires_at, expiry_provenance, review_status)
                            SELECT
                             :id, receipt.id, :rawName, NULL,
                             NULL, :quantity, :unit, :unitPrice,
                             1.0000, TRUE, :quantity, :unit,
                             NULL, 'UNRESOLVED', 'PENDING'
                            FROM receipt_imports receipt
                            WHERE receipt.id = :receipt
                              AND receipt.kitchen_id = :workspaceKitchen
                              AND receipt.location_id = :workspaceLocation
                            """)
                    .param("id", lineId)
                    .param("receipt", receiptId)
                    .param("rawName", command.rawName().trim())
                    .param("quantity", command.quantity())
                    .param("unit", command.unit().trim())
                    .param("unitPrice", command.unitPrice())
                    .update();
            if (inserted != 1) {
                throw new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Receipt not found."
                );
            }

            advanceVersion(receiptId, expectedVersion);
            return queries.require(receiptId);
        });
    }

    private ReceiptView process(
            String id,
            ReceiptEvidence evidence
    ) {
        ReceiptExtractor.Extraction extraction;
        try {
            extraction = extractor.extract(
                    evidence.uri(),
                    evidence.filename(),
                    evidence.contentType()
            );
            validateExtraction(extraction);
        } catch (RuntimeException error) {
            return transitionToManualReview(
                    id,
                    "EXTRACTION",
                    "EXTRACTION_FAILED",
                    "Automatic extraction failed. The private evidence was "
                            + "retained; retry or continue with manual review.",
                    error
            );
        }

        List<ExtractedLine> lines;
        try {
            lines = normalize(extraction);
        } catch (RuntimeException error) {
            return transitionToManualReview(
                    id,
                    "NORMALIZATION",
                    "NORMALIZATION_FAILED",
                    "Extracted lines could not be normalized safely. The "
                            + "private evidence was retained; retry or continue "
                            + "with manual review.",
                    error
            );
        }

        try {
            transactions.executeWithoutResult(status -> {
                for (ExtractedLine line : lines) {
                    insertLine(id, line);
                }
                Instant completedAt = Instant.now();
                int changed = scopedSql("""
                                UPDATE receipt_imports
                                SET status = 'REVIEW_REQUIRED',
                                    merchant = :merchant,
                                    purchase_date = :purchaseDate,
                                    total = :total,
                                    extraction_provider = :provider,
                                    extraction_model = :model,
                                    extraction_schema_version = :schemaVersion,
                                    extracted_at = :now,
                                    failure_code = NULL,
                                    failure_message = NULL,
                                    updated_at = :now,
                                    version = version + 1
                                WHERE id = :id
                                  AND kitchen_id = :workspaceKitchen
                                  AND location_id = :workspaceLocation
                                  AND status = 'EXTRACTING'
                                """)
                        .param("merchant", extraction.merchant())
                        .param("purchaseDate", extraction.purchaseDate())
                        .param("total", extraction.total())
                        .param("provider", extractor.provider())
                        .param("model", extractor.model())
                        .param("schemaVersion", extractor.schemaVersion())
                        .param("now", JdbcTimestamp.utc(completedAt))
                        .param("id", id)
                        .update();
                if (changed != 1) {
                    throw new IllegalStateException(
                            "Receipt extraction state changed unexpectedly."
                    );
                }
                completeAttempt(id, "SUCCEEDED", null, null, null, completedAt);
                repository.addActivity(
                        "Receipt",
                        "Uploaded " + evidence.filename() + " for review"
                );
            });
        } catch (RuntimeException error) {
            return transitionToManualReview(
                    id,
                    "PERSISTENCE",
                    "PERSISTENCE_FAILED",
                    "Extracted lines could not be persisted safely. The "
                            + "private evidence was retained; retry or continue "
                            + "with manual review.",
                    error
            );
        }

        return queries.require(id);
    }

    private ReceiptView transitionToManualReview(
            String id,
            String failureStage,
            String failureCode,
            String safeMessage,
            RuntimeException processingFailure
    ) {
        try {
            transactions.executeWithoutResult(status -> {
                Instant completedAt = Instant.now();
                int changed = scopedSql("""
                                UPDATE receipt_imports
                                SET status = 'REVIEW_REQUIRED',
                                    failure_code = :failureCode,
                                    failure_message = :safeMessage,
                                    extraction_provider = :provider,
                                    extraction_model = :model,
                                    extraction_schema_version = :schemaVersion,
                                    updated_at = :now,
                                    version = version + 1
                                WHERE id = :id
                                  AND kitchen_id = :workspaceKitchen
                                  AND location_id = :workspaceLocation
                                  AND status = 'EXTRACTING'
                                """)
                        .param("failureCode", failureCode)
                        .param("safeMessage", safeMessage)
                        .param("provider", extractor.provider())
                        .param("model", extractor.model())
                        .param("schemaVersion", extractor.schemaVersion())
                        .param("now", JdbcTimestamp.utc(completedAt))
                        .param("id", id)
                        .update();
                if (changed != 1) {
                    throw new IllegalStateException(
                            "Receipt could not enter manual review."
                    );
                }
                completeAttempt(
                        id,
                        "MANUAL_REVIEW",
                        failureStage,
                        failureCode,
                        failureDetail(processingFailure),
                        completedAt
                );
            });
        } catch (RuntimeException transitionFailure) {
            transitionFailure.addSuppressed(processingFailure);
            throw transitionFailure;
        }
        return queries.require(id);
    }

    private void transitionToExtracting(String id) {
        int changed = scopedSql("""
                        UPDATE receipt_imports
                        SET status = 'EXTRACTING',
                            updated_at = :now,
                            version = version + 1
                        WHERE id = :id
                          AND kitchen_id = :workspaceKitchen
                          AND location_id = :workspaceLocation
                          AND status = 'UPLOADED'
                        """)
                .param("now", JdbcTimestamp.utc(Instant.now()))
                .param("id", id)
                .update();
        if (changed != 1) {
            throw new IllegalStateException(
                    "Receipt could not enter extraction."
            );
        }
    }

    private void beginAttempt(
            String receiptId,
            int attemptNumber,
            String trigger,
            Instant startedAt
    ) {
        int inserted = scopedSql("""
                        INSERT INTO receipt_processing_attempts
                        (id, receipt_id, kitchen_id, location_id,
                         attempt_number, attempt_trigger, outcome,
                         extraction_provider, extraction_model,
                         extraction_schema_version, started_at)
                        SELECT
                         :id, receipt.id, :workspaceKitchen, :workspaceLocation,
                         :attemptNumber, :attemptTrigger, 'IN_PROGRESS',
                         :provider, :model, :schemaVersion, :startedAt
                        FROM receipt_imports receipt
                        WHERE receipt.id = :receiptId
                          AND receipt.kitchen_id = :workspaceKitchen
                          AND receipt.location_id = :workspaceLocation
                        """)
                .param("id", processingAttemptId())
                .param("receiptId", receiptId)
                .param("attemptNumber", attemptNumber)
                .param("attemptTrigger", trigger)
                .param("provider", extractor.provider())
                .param("model", extractor.model())
                .param("schemaVersion", extractor.schemaVersion())
                .param("startedAt", JdbcTimestamp.utc(startedAt))
                .update();
        if (inserted != 1) {
            throw new IllegalStateException(
                    "Receipt processing attempt could not be audited."
            );
        }
    }

    private void completeAttempt(
            String receiptId,
            String outcome,
            String failureStage,
            String failureCode,
            String failureDetail,
            Instant completedAt
    ) {
        int changed = scopedSql("""
                        UPDATE receipt_processing_attempts
                        SET outcome = :outcome,
                            failure_stage = :failureStage,
                            failure_code = :failureCode,
                            failure_detail = :failureDetail,
                            completed_at = :completedAt
                        WHERE receipt_id = :receiptId
                          AND kitchen_id = :workspaceKitchen
                          AND location_id = :workspaceLocation
                          AND outcome = 'IN_PROGRESS'
                        """)
                .param("outcome", outcome)
                .param("failureStage", failureStage)
                .param("failureCode", failureCode)
                .param("failureDetail", failureDetail)
                .param("completedAt", JdbcTimestamp.utc(completedAt))
                .param("receiptId", receiptId)
                .update();
        if (changed != 1) {
            throw new IllegalStateException(
                    "Receipt processing attempt could not be completed."
            );
        }
    }

    private void compensateStoredEvidence(
            String receiptId,
            ReceiptFileStore.StoredReceipt stored,
            RuntimeException persistenceFailure
    ) {
        try {
            fileStore.delete(stored);
        } catch (RuntimeException cleanupFailure) {
            persistenceFailure.addSuppressed(cleanupFailure);
            LOGGER.error(
                    "receipt_orphan_cleanup_failed receiptId={}",
                    receiptId,
                    cleanupFailure
            );
        }
    }

    private List<ExtractedLine> normalize(
            ReceiptExtractor.Extraction extraction
    ) {
        List<ExtractedLine> result = new ArrayList<>();
        for (ReceiptExtractor.Line line : extraction.lines()) {
            requireText(line.rawName(), "Receipt line name");
            requireText(line.unit(), "Receipt line unit");
            if (line.quantity() == null || line.quantity().signum() <= 0) {
                throw new IllegalArgumentException(
                        "Receipt line quantity must be positive."
                );
            }
            if (line.confidence() == null
                    || line.confidence().compareTo(BigDecimal.ZERO) < 0
                    || line.confidence().compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException(
                        "Receipt confidence must be between 0 and 1."
                );
            }
            if (line.unitPrice() != null && line.unitPrice().signum() < 0) {
                throw new IllegalArgumentException(
                        "Receipt unit price cannot be negative."
                );
            }

            var match = normalizer.normalize(line.rawName());
            String ingredientId = match.map(
                    ProductNormalizer.Match::ingredientId
            ).orElse(null);
            String canonicalName = match.map(
                    ProductNormalizer.Match::canonicalName
            ).orElse(null);
            BigDecimal confidence = line.confidence().min(
                    BigDecimal.valueOf(match.map(
                            ProductNormalizer.Match::confidence
                    ).orElse(0.0))
            );
            BigDecimal quantity = line.quantity();
            String unit = line.unit().trim();

            if (ingredientId != null) {
                Ingredient ingredient = repository.ingredient(ingredientId)
                        .orElseThrow();
                try {
                    CanonicalQuantity canonical = units.toIngredientBase(
                            ingredient,
                            line.quantity(),
                            line.unit()
                    );
                    quantity = canonical.quantity();
                    unit = canonical.unit();
                } catch (IncompatibleUnitException ignored) {
                    // Preserve the source value. Review cannot be bypassed, and
                    // confirmation will reject it until an operator corrects it.
                }
            } else {
                try {
                    units.canonicalize(line.quantity(), line.unit());
                } catch (IncompatibleUnitException ignored) {
                    // Unknown units remain verbatim evidence and must be
                    // corrected during persisted review.
                }
            }

            result.add(new ExtractedLine(
                    UUID.randomUUID().toString(),
                    line.rawName().trim(),
                    ingredientId,
                    canonicalName,
                    quantity,
                    unit,
                    line.quantity(),
                    line.unit().trim(),
                    line.unitPrice(),
                    confidence
            ));
        }
        return List.copyOf(result);
    }

    private void validateExtraction(ReceiptExtractor.Extraction extraction) {
        if (extraction == null || extraction.lines() == null) {
            throw new IllegalArgumentException(
                    "Receipt extractor returned no valid result."
            );
        }
        if (extraction.total() != null && extraction.total().signum() < 0) {
            throw new IllegalArgumentException(
                    "Receipt total cannot be negative."
            );
        }
    }

    private void insertLine(String receiptId, ExtractedLine line) {
        int inserted = scopedSql("""
                        INSERT INTO receipt_items
                        (id, receipt_id, raw_name, ingredient_id,
                         canonical_name, quantity, unit, unit_price,
                         confidence, selected, source_quantity, source_unit,
                         expires_at, expiry_provenance, review_status)
                        SELECT
                         :id, receipt.id, :rawName, :ingredient,
                         :canonicalName, :quantity, :unit, :unitPrice,
                         :confidence, TRUE, :sourceQuantity, :sourceUnit,
                         NULL, 'UNRESOLVED', 'PENDING'
                        FROM receipt_imports receipt
                        WHERE receipt.id = :receipt
                          AND receipt.kitchen_id = :workspaceKitchen
                          AND receipt.location_id = :workspaceLocation
                        """)
                .param("id", line.id())
                .param("receipt", receiptId)
                .param("rawName", line.rawName())
                .param("ingredient", line.ingredientId())
                .param("canonicalName", line.canonicalName())
                .param("quantity", line.quantity())
                .param("unit", line.unit())
                .param("unitPrice", line.unitPrice())
                .param("confidence", line.confidence())
                .param("sourceQuantity", line.sourceQuantity())
                .param("sourceUnit", line.sourceUnit())
                .update();
        if (inserted != 1) {
            throw new IllegalStateException(
                    "Receipt extraction target left this workspace."
            );
        }
    }

    private ReceiptState lock(String id) {
        return scopedSql("""
                        SELECT id, status, version
                        FROM receipt_imports
                        WHERE id = :id
                          AND kitchen_id = :workspaceKitchen
                          AND location_id = :workspaceLocation
                        FOR UPDATE
                        """)
                .param("id", id)
                .query((rs, row) -> new ReceiptState(
                        rs.getString("id"),
                        ReceiptImport.Status.valueOf(rs.getString("status")),
                        rs.getInt("version")
                ))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Receipt not found."
                ));
    }

    private void requireReviewable(ReceiptState receipt, int expectedVersion) {
        if (receipt.status() != ReceiptImport.Status.REVIEW_REQUIRED) {
            throw conflict("Receipt is not available for review.");
        }
        if (receipt.version() != expectedVersion) {
            throw conflict("Receipt changed; reload the latest review.");
        }
    }

    private void advanceVersion(String id, int expectedVersion) {
        int changed = scopedSql("""
                        UPDATE receipt_imports
                        SET version = version + 1,
                            updated_at = :now
                        WHERE id = :id
                          AND kitchen_id = :workspaceKitchen
                          AND location_id = :workspaceLocation
                          AND version = :version
                        """)
                .param("now", JdbcTimestamp.utc(Instant.now()))
                .param("id", id)
                .param("version", expectedVersion)
                .update();
        if (changed != 1) {
            throw conflict("Receipt changed; reload the latest review.");
        }
    }

    private JdbcClient.StatementSpec scopedSql(String sql) {
        return jdbc.sql(sql)
                .param("workspaceKitchen", workspace.kitchenId())
                .param("workspaceLocation", workspace.locationId());
    }

    private static String receiptId() {
        return "RCT-" + UUID.randomUUID()
                .toString()
                .toUpperCase(Locale.ROOT);
    }

    private static String processingAttemptId() {
        return "RPA-" + UUID.randomUUID()
                .toString()
                .toUpperCase(Locale.ROOT);
    }

    private static String failureDetail(RuntimeException error) {
        String message = error.getMessage() == null
                ? error.getClass().getSimpleName()
                : error.getClass().getSimpleName() + ": " + error.getMessage();
        String sanitized = message.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "?");
        return sanitized.substring(0, Math.min(sanitized.length(), 1000));
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required.");
        }
    }

    public record ManualLine(
            String rawName,
            BigDecimal quantity,
            String unit,
            BigDecimal unitPrice
    ) {
    }

    private record ReceiptEvidence(
            String uri,
            String filename,
            String contentType
    ) {
    }

    private record RetryableReceipt(
            String id,
            String objectUri,
            String originalFilename,
            String contentType,
            ReceiptImport.Status status,
            int version,
            String failureCode
    ) {
    }

    private record ExtractedLine(
            String id,
            String rawName,
            String ingredientId,
            String canonicalName,
            BigDecimal quantity,
            String unit,
            BigDecimal sourceQuantity,
            String sourceUnit,
            BigDecimal unitPrice,
            BigDecimal confidence
    ) {
    }

    private record ReceiptState(
            String id,
            ReceiptImport.Status status,
            int version
    ) {
    }
}
