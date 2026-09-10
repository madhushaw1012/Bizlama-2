package com.bizlama.api.receipts;

import com.bizlama.api.config.JdbcTimestamp;
import com.bizlama.api.config.WorkspaceProperties;
import com.bizlama.api.domain.Ingredient;
import com.bizlama.api.domain.ReceiptImport;
import com.bizlama.api.quantity.CanonicalQuantity;
import com.bizlama.api.quantity.UnitConversionService;
import com.bizlama.api.shelflife.ShelfLifeGuidanceProvider;
import com.bizlama.api.stock.ExpiryProvenance;
import com.bizlama.api.stock.InventoryPurchaseService;
import com.bizlama.api.store.OperationalRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReceiptReviewService {

    private final JdbcClient jdbc;
    private final UnitConversionService units;
    private final ShelfLifeGuidanceProvider shelfLife;
    private final InventoryPurchaseService purchases;
    private final OperationalRepository repository;
    private final ReceiptQueryService queries;
    private final WorkspaceProperties workspace;

    public ReceiptReviewService(
            JdbcClient jdbc,
            UnitConversionService units,
            ShelfLifeGuidanceProvider shelfLife,
            InventoryPurchaseService purchases,
            OperationalRepository repository,
            ReceiptQueryService queries,
            WorkspaceProperties workspace
    ) {
        this.jdbc = jdbc;
        this.units = units;
        this.shelfLife = shelfLife;
        this.purchases = purchases;
        this.repository = repository;
        this.queries = queries;
        this.workspace = workspace;
    }

    /**
     * Persists the operator's corrections. Confirmation never accepts these
     * mutable values; it reloads this version under a database lock.
     */
    @Transactional
    public ReceiptView review(
            String receiptId,
            ReviewCommand command,
            String actor
    ) {
        requireText(actor, "Reviewer");
        if (command.purchaseDate() == null) {
            throw invalid("Purchase date must be explicitly reviewed.");
        }

        LockedReceipt receipt = lock(receiptId);
        requireReviewable(receipt, command.expectedVersion());

        List<StoredLine> storedLines = lines(receiptId);
        if (storedLines.isEmpty()) {
            throw invalid("Add at least one persisted receipt line before review.");
        }

        Map<String, LineReview> submitted = new HashMap<>();
        for (LineReview line : command.lines()) {
            if (line.id() == null
                    || submitted.putIfAbsent(line.id(), line) != null) {
                throw invalid("Each persisted receipt line must appear exactly once.");
            }
        }
        if (!submitted.keySet().equals(
                storedLines.stream()
                        .map(StoredLine::id)
                        .collect(java.util.stream.Collectors.toSet())
        )) {
            throw invalid(
                    "Review must reference the complete current set of persisted line IDs."
            );
        }

        Instant now = Instant.now();
        for (StoredLine stored : storedLines) {
            LineReview review = submitted.get(stored.id());
            if (!review.selected()) {
                reject(receipt, stored.id(), actor, now);
                continue;
            }
            reviewSelected(
                    receipt,
                    stored,
                    review,
                    command.purchaseDate(),
                    actor,
                    now
            );
        }

        int changed = jdbc.sql("""
                        UPDATE receipt_imports
                        SET purchase_date = :purchaseDate,
                            failure_code = NULL,
                            failure_message = NULL,
                            version = version + 1,
                            updated_at = :now
                        WHERE id = :id
                          AND kitchen_id = :kitchen
                          AND location_id = :location
                          AND version = :version
                          AND status = 'REVIEW_REQUIRED'
                        """)
                .param("purchaseDate", command.purchaseDate())
                .param("now", JdbcTimestamp.utc(now))
                .param("id", receiptId)
                .param("kitchen", receipt.kitchenId())
                .param("location", receipt.locationId())
                .param("version", command.expectedVersion())
                .update();
        if (changed != 1) {
            throw conflict("Receipt changed; reload the latest review.");
        }

        return queries.require(receiptId);
    }

    /**
     * Atomically creates every approved lot/movement and confirms exactly one
     * receipt version. A retry with the same key returns the first result.
     */
    @Transactional
    public ReceiptView confirm(
            String receiptId,
            ConfirmCommand command,
            String actor
    ) {
        requireText(actor, "Confirmer");
        requireText(command.idempotencyKey(), "Idempotency key");
        if (command.idempotencyKey().length() > 200) {
            throw invalid("Idempotency key is too long.");
        }

        LockedReceipt receipt = lock(receiptId);
        if (receipt.status() == ReceiptImport.Status.CONFIRMED) {
            if (command.idempotencyKey().equals(
                    receipt.confirmationIdempotencyKey()
            )) {
                return queries.require(receiptId);
            }
            throw conflict("Receipt was already confirmed with another request.");
        }
        requireReviewable(receipt, command.expectedVersion());

        String usedBy = jdbc.sql("""
                        SELECT id
                        FROM receipt_imports
                        WHERE confirmation_idempotency_key = :key
                          AND kitchen_id = :kitchen
                          AND location_id = :location
                          AND id <> :id
                        """)
                .param("key", command.idempotencyKey())
                .param("kitchen", receipt.kitchenId())
                .param("location", receipt.locationId())
                .param("id", receiptId)
                .query(String.class)
                .optional()
                .orElse(null);
        if (usedBy != null) {
            throw conflict("Idempotency key is already used by another receipt.");
        }

        List<StoredLine> storedLines = lines(receiptId);
        if (storedLines.stream().anyMatch(
                line -> line.reviewStatus() == ReceiptView.ReviewStatus.PENDING
        )) {
            throw invalid("Every persisted line must be reviewed before confirmation.");
        }
        List<StoredLine> approved = storedLines.stream()
                .filter(StoredLine::selected)
                .filter(line ->
                        line.reviewStatus() == ReceiptView.ReviewStatus.APPROVED
                )
                .toList();
        if (approved.isEmpty()) {
            throw invalid("Select and approve at least one receipt line.");
        }
        if (receipt.purchaseDate() == null) {
            throw invalid("Purchase date must be reviewed before confirmation.");
        }

        Instant now = Instant.now();
        for (StoredLine line : approved) {
            if (line.ingredientId() == null
                    || line.quantity() == null
                    || line.unit() == null
                    || line.expiresAt() == null
                    || line.expiryProvenance() == ExpiryProvenance.UNRESOLVED) {
                throw invalid(
                        "Approved receipt line is missing validated inventory data."
                );
            }
            purchases.add(new InventoryPurchaseService.Purchase(
                    "receipt-" + receiptId + "-" + line.id(),
                    receipt.kitchenId(),
                    receipt.locationId(),
                    line.ingredientId(),
                    line.quantity(),
                    line.unit(),
                    line.sourceQuantity(),
                    line.sourceUnit(),
                    receipt.purchaseDate(),
                    line.expiresAt(),
                    line.expiryProvenance(),
                    "receipt:" + receiptId,
                    "receipt",
                    receiptId,
                    receiptId,
                    line.id(),
                    now
            ));
        }

        int changed = jdbc.sql("""
                        UPDATE receipt_imports
                        SET status = 'CONFIRMED',
                            confirmed_at = :now,
                            confirmed_by = :actor,
                            confirmation_idempotency_key = :key,
                            failure_code = NULL,
                            failure_message = NULL,
                            updated_at = :now,
                            version = version + 1
                        WHERE id = :id
                          AND kitchen_id = :kitchen
                          AND location_id = :location
                          AND version = :version
                          AND status = 'REVIEW_REQUIRED'
                        """)
                .param("now", JdbcTimestamp.utc(now))
                .param("actor", actor)
                .param("key", command.idempotencyKey())
                .param("id", receiptId)
                .param("kitchen", receipt.kitchenId())
                .param("location", receipt.locationId())
                .param("version", command.expectedVersion())
                .update();
        if (changed != 1) {
            throw conflict("Receipt changed; no inventory was committed.");
        }

        repository.addActivity("Receipt", "Confirmed receipt " + receiptId);
        return queries.require(receiptId);
    }

    private void reviewSelected(
            LockedReceipt receipt,
            StoredLine stored,
            LineReview review,
            LocalDate purchaseDate,
            String actor,
            Instant now
    ) {
        requireText(review.ingredientId(), "Ingredient");
        requireText(review.unit(), "Line unit");
        if (review.quantity() == null || review.quantity().signum() <= 0) {
            throw invalid("Selected line quantity must be positive.");
        }

        Ingredient ingredient = jdbc.sql("""
                        SELECT id, name, base_unit, active
                        FROM ingredients
                        WHERE id = :ingredient
                          AND kitchen_id = :kitchen
                          AND active = TRUE
                        """)
                .param("ingredient", review.ingredientId())
                .param("kitchen", receipt.kitchenId())
                .query((rs, row) -> new Ingredient(
                        rs.getString("id"),
                        rs.getString("name"),
                        rs.getString("base_unit"),
                        rs.getBoolean("active")
                ))
                .optional()
                .orElseThrow(() -> invalid(
                        "Selected line needs an active ingredient in this kitchen."
                ));

        CanonicalQuantity canonical = units.toIngredientBase(
                ingredient,
                review.quantity(),
                review.unit()
        );
        ExpiryResolution expiry = resolveExpiry(
                review,
                review.ingredientId(),
                purchaseDate
        );

        int changed = jdbc.sql("""
                        UPDATE receipt_items
                        SET ingredient_id = :ingredient,
                            canonical_name = :canonicalName,
                            quantity = :quantity,
                            unit = :unit,
                            selected = TRUE,
                            expires_at = :expiresAt,
                            expiry_provenance = :provenance,
                            review_status = 'APPROVED',
                            reviewed_at = :reviewedAt,
                            reviewed_by = :reviewedBy
                        WHERE receipt_id = :receipt
                          AND id = :id
                          AND EXISTS (
                            SELECT 1
                            FROM receipt_imports scoped_receipt
                            WHERE scoped_receipt.id = receipt_items.receipt_id
                              AND scoped_receipt.kitchen_id = :kitchen
                              AND scoped_receipt.location_id = :location)
                        """)
                .param("ingredient", ingredient.id())
                .param("canonicalName", ingredient.name())
                .param("quantity", canonical.quantity())
                .param("unit", canonical.unit())
                .param("expiresAt", expiry.date())
                .param("provenance", expiry.provenance().name())
                .param("reviewedAt", JdbcTimestamp.utc(now))
                .param("reviewedBy", actor)
                .param("receipt", receipt.id())
                .param("kitchen", receipt.kitchenId())
                .param("location", receipt.locationId())
                .param("id", stored.id())
                .update();
        if (changed != 1) {
            throw conflict("Receipt line changed during review.");
        }
    }

    private ExpiryResolution resolveExpiry(
            LineReview review,
            String ingredientId,
            LocalDate purchaseDate
    ) {
        if (review.expiryProvenance()
                == ExpiryProvenance.REVIEWED_SHELF_LIFE_RULE) {
            if (review.expiresAt() != null) {
                throw invalid(
                        "Reviewed shelf-life expiry is calculated server-side."
                );
            }
            LocalDate date = shelfLife.findForIngredient(ingredientId)
                    .map(rule -> rule.expiresOn(purchaseDate))
                    .orElseThrow(() -> invalid(
                            "No reviewed shelf-life rule exists for this ingredient."
                    ));
            return new ExpiryResolution(
                    date,
                    ExpiryProvenance.REVIEWED_SHELF_LIFE_RULE
            );
        }
        if (review.expiryProvenance() != ExpiryProvenance.PRINTED_DATE
                && review.expiryProvenance()
                    != ExpiryProvenance.OWNER_CONFIRMED) {
            throw invalid(
                    "Expiry must come from a printed date, owner confirmation, "
                            + "or a reviewed shelf-life rule."
            );
        }
        if (review.expiresAt() == null) {
            throw invalid("Explicit expiry date is required for this provenance.");
        }
        if (review.expiresAt().isBefore(purchaseDate)) {
            throw invalid("Expiry cannot be before the purchase date.");
        }
        return new ExpiryResolution(
                review.expiresAt(),
                review.expiryProvenance()
        );
    }

    private void reject(
            LockedReceipt receipt,
            String lineId,
            String actor,
            Instant now
    ) {
        int changed = jdbc.sql("""
                        UPDATE receipt_items
                        SET selected = FALSE,
                            review_status = 'REJECTED',
                            reviewed_at = :reviewedAt,
                            reviewed_by = :reviewedBy
                        WHERE receipt_id = :receipt
                          AND id = :id
                          AND EXISTS (
                            SELECT 1
                            FROM receipt_imports scoped_receipt
                            WHERE scoped_receipt.id = receipt_items.receipt_id
                              AND scoped_receipt.kitchen_id = :kitchen
                              AND scoped_receipt.location_id = :location)
                        """)
                .param("reviewedAt", JdbcTimestamp.utc(now))
                .param("reviewedBy", actor)
                .param("receipt", receipt.id())
                .param("kitchen", receipt.kitchenId())
                .param("location", receipt.locationId())
                .param("id", lineId)
                .update();
        if (changed != 1) {
            throw conflict("Receipt line changed during review.");
        }
    }

    private LockedReceipt lock(String id) {
        return jdbc.sql("""
                        SELECT id, kitchen_id, location_id, status, version,
                               purchase_date, confirmation_idempotency_key
                        FROM receipt_imports
                        WHERE id = :id
                          AND kitchen_id = :kitchen
                          AND location_id = :location
                        FOR UPDATE
                        """)
                .param("id", id)
                .param("kitchen", workspace.kitchenId())
                .param("location", workspace.locationId())
                .query((rs, row) -> new LockedReceipt(
                        rs.getString("id"),
                        rs.getString("kitchen_id"),
                        rs.getString("location_id"),
                        ReceiptImport.Status.valueOf(rs.getString("status")),
                        rs.getInt("version"),
                        rs.getObject("purchase_date", LocalDate.class),
                        rs.getString("confirmation_idempotency_key")
                ))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Receipt not found."
                ));
    }

    private List<StoredLine> lines(String receiptId) {
        return jdbc.sql("""
                        SELECT id, ingredient_id, quantity, unit,
                               source_quantity, source_unit, selected,
                               expires_at, expiry_provenance, review_status
                        FROM receipt_items item
                        WHERE item.receipt_id = :receipt
                          AND EXISTS (
                            SELECT 1
                            FROM receipt_imports receipt
                            WHERE receipt.id = item.receipt_id
                              AND receipt.kitchen_id = :kitchen
                              AND receipt.location_id = :location)
                        ORDER BY item.id
                        """)
                .param("receipt", receiptId)
                .param("kitchen", workspace.kitchenId())
                .param("location", workspace.locationId())
                .query((rs, row) -> new StoredLine(
                        rs.getString("id"),
                        rs.getString("ingredient_id"),
                        rs.getBigDecimal("quantity"),
                        rs.getString("unit"),
                        rs.getBigDecimal("source_quantity"),
                        rs.getString("source_unit"),
                        rs.getBoolean("selected"),
                        rs.getObject("expires_at", LocalDate.class),
                        ExpiryProvenance.valueOf(
                                rs.getString("expiry_provenance")
                        ),
                        ReceiptView.ReviewStatus.valueOf(
                                rs.getString("review_status")
                        )
                ))
                .list();
    }

    private void requireReviewable(
            LockedReceipt receipt,
            int expectedVersion
    ) {
        if (receipt.status() != ReceiptImport.Status.REVIEW_REQUIRED) {
            throw conflict("Receipt is not available for review.");
        }
        if (receipt.version() != expectedVersion) {
            throw conflict("Receipt changed; reload the latest review.");
        }
    }

    private ResponseStatusException invalid(String message) {
        return new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                message
        );
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required.");
        }
    }

    public record ReviewCommand(
            int expectedVersion,
            LocalDate purchaseDate,
            List<LineReview> lines
    ) {
        public ReviewCommand {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    public record LineReview(
            String id,
            boolean selected,
            String ingredientId,
            BigDecimal quantity,
            String unit,
            LocalDate expiresAt,
            ExpiryProvenance expiryProvenance
    ) {
    }

    public record ConfirmCommand(
            int expectedVersion,
            String idempotencyKey
    ) {
    }

    private record LockedReceipt(
            String id,
            String kitchenId,
            String locationId,
            ReceiptImport.Status status,
            int version,
            LocalDate purchaseDate,
            String confirmationIdempotencyKey
    ) {
    }

    private record StoredLine(
            String id,
            String ingredientId,
            BigDecimal quantity,
            String unit,
            BigDecimal sourceQuantity,
            String sourceUnit,
            boolean selected,
            LocalDate expiresAt,
            ExpiryProvenance expiryProvenance,
            ReceiptView.ReviewStatus reviewStatus
    ) {
    }

    private record ExpiryResolution(
            LocalDate date,
            ExpiryProvenance provenance
    ) {
    }
}
