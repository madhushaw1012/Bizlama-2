package com.bizlama.api.stock;

import com.bizlama.api.config.JdbcTimestamp;
import com.bizlama.api.domain.Ingredient;
import com.bizlama.api.domain.StockLot;
import com.bizlama.api.quantity.CanonicalQuantity;
import com.bizlama.api.quantity.UnitConversionService;
import com.bizlama.api.outbox.OutboxEventDraft;
import com.bizlama.api.outbox.TransactionalOutboxService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only production path for adding an inventory lot and its matching movement.
 */
@Service
public class InventoryPurchaseService {

    private final JdbcClient jdbc;
    private final UnitConversionService units;
    private final TransactionalOutboxService outbox;

    public InventoryPurchaseService(
            JdbcClient jdbc,
            UnitConversionService units,
            TransactionalOutboxService outbox
    ) {
        this.jdbc = jdbc;
        this.units = units;
        this.outbox = outbox;
    }

    @Transactional
    public StockLot add(Purchase purchase) {
        Objects.requireNonNull(purchase, "Purchase is required.");
        requireText(purchase.kitchenId(), "Kitchen");
        requireText(purchase.locationId(), "Location");
        requireText(purchase.ingredientId(), "Ingredient");
        requireText(purchase.source(), "Source");
        requireText(purchase.referenceType(), "Reference type");
        requireText(purchase.referenceId(), "Reference ID");
        Objects.requireNonNull(purchase.purchasedAt(), "Purchase date is required.");
        Objects.requireNonNull(purchase.expiryProvenance(), "Expiry provenance is required.");
        Objects.requireNonNull(purchase.occurredAt(), "Occurrence time is required.");
        LocalDate occurrenceDate = purchase.occurredAt()
                .atZone(kitchenZone(purchase.kitchenId()))
                .toLocalDate();
        if (purchase.purchasedAt().isAfter(occurrenceDate)) {
            throw new FutureDatedPurchaseException(
                    purchase.purchasedAt(),
                    occurrenceDate
            );
        }

        if (purchase.quantity() == null || purchase.quantity().signum() <= 0) {
            throw new IllegalArgumentException("Purchase quantity must be positive.");
        }
        requireText(purchase.unit(), "Purchase unit");
        BigDecimal sourceQuantity = purchase.sourceQuantity() == null
                ? purchase.quantity()
                : purchase.sourceQuantity();
        String sourceUnit = purchase.sourceUnit() == null
                ? purchase.unit()
                : purchase.sourceUnit();
        if (sourceQuantity.signum() <= 0) {
            throw new IllegalArgumentException("Source quantity must be positive.");
        }
        requireText(sourceUnit, "Source unit");

        if (purchase.expiresAt() == null
                && purchase.expiryProvenance() != ExpiryProvenance.UNRESOLVED) {
            throw new IllegalArgumentException(
                    "An expiry provenance requires an expiry date."
            );
        }
        if (purchase.expiresAt() != null
                && purchase.expiryProvenance() == ExpiryProvenance.UNRESOLVED) {
            throw new IllegalArgumentException(
                    "An expiry date requires explicit provenance."
            );
        }
        if (purchase.expiresAt() != null
                && purchase.expiresAt().isBefore(purchase.purchasedAt())) {
            throw new IllegalArgumentException(
                    "Expiry cannot be before the purchase date."
            );
        }

        Ingredient ingredient = jdbc.sql("""
                        SELECT id, name, base_unit, active
                        FROM ingredients
                        WHERE id = :ingredient
                          AND kitchen_id = :kitchen
                          AND active = TRUE
                        """)
                .param("ingredient", purchase.ingredientId())
                .param("kitchen", purchase.kitchenId())
                .query((rs, row) -> new Ingredient(
                        rs.getString("id"),
                        rs.getString("name"),
                        rs.getString("base_unit"),
                        rs.getBoolean("active")
                ))
                .optional()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Active ingredient not found in kitchen: "
                                + purchase.ingredientId()
                ));

        boolean locationExists = jdbc.sql("""
                        SELECT COUNT(*)
                        FROM kitchen_locations
                        WHERE id = :location
                          AND kitchen_id = :kitchen
                          AND active = TRUE
                        """)
                .param("location", purchase.locationId())
                .param("kitchen", purchase.kitchenId())
                .query(Long.class)
                .single() == 1L;
        if (!locationExists) {
            throw new IllegalArgumentException(
                    "Active location not found in kitchen: " + purchase.locationId()
            );
        }

        CanonicalQuantity canonical = units.toIngredientBase(
                ingredient,
                purchase.quantity(),
                purchase.unit()
        );
        String lotId = purchase.lotId() == null || purchase.lotId().isBlank()
                ? UUID.randomUUID().toString()
                : purchase.lotId();
        String status = purchase.expiresAt() == null
                ? "QUARANTINED"
                : "AVAILABLE";

        jdbc.sql("""
                        INSERT INTO stock_lots
                        (id, ingredient_id, quantity_remaining, unit,
                         purchased_at, expires_at, source, kitchen_id,
                         location_id, status, version, expiry_provenance,
                         source_quantity, source_unit, receipt_id, receipt_item_id)
                        VALUES
                        (:id, :ingredient, :quantity, :unit,
                         :purchasedAt, :expiresAt, :source, :kitchen,
                         :location, :status, 1, :provenance,
                         :sourceQuantity, :sourceUnit, :receiptId, :receiptItemId)
                        """)
                .param("id", lotId)
                .param("ingredient", purchase.ingredientId())
                .param("quantity", canonical.quantity())
                .param("unit", canonical.unit())
                .param("purchasedAt", purchase.purchasedAt())
                .param("expiresAt", purchase.expiresAt())
                .param("source", purchase.source())
                .param("kitchen", purchase.kitchenId())
                .param("location", purchase.locationId())
                .param("status", status)
                .param("provenance", purchase.expiryProvenance().name())
                .param("sourceQuantity", sourceQuantity)
                .param("sourceUnit", sourceUnit.trim())
                .param("receiptId", purchase.receiptId())
                .param("receiptItemId", purchase.receiptItemId())
                .update();

        jdbc.sql("""
                        INSERT INTO stock_movements
                        (id, stock_lot_id, ingredient_id, movement_type,
                         quantity_change, unit, reference_type, reference_id,
                         occurred_at, kitchen_id, location_id)
                        VALUES
                        (:id, :lot, :ingredient, 'PURCHASE',
                         :quantity, :unit, :referenceType, :referenceId,
                         :occurredAt, :kitchen, :location)
                        """)
                .param("id", UUID.randomUUID().toString())
                .param("lot", lotId)
                .param("ingredient", purchase.ingredientId())
                .param("quantity", canonical.quantity())
                .param("unit", canonical.unit())
                .param("referenceType", purchase.referenceType())
                .param("referenceId", purchase.referenceId())
                .param("occurredAt", JdbcTimestamp.utc(purchase.occurredAt()))
                .param("kitchen", purchase.kitchenId())
                .param("location", purchase.locationId())
                .update();

        outbox.append(new OutboxEventDraft(
                null,
                "INVENTORY_PURCHASED",
                1,
                purchase.kitchenId(),
                "stock_lot",
                lotId,
                purchase.occurredAt(),
                purchase.referenceId(),
                null,
                "stock-purchase:" + lotId,
                new PurchaseEventPayload(
                        lotId,
                        purchase.locationId(),
                        purchase.ingredientId(),
                        canonical.quantity(),
                        canonical.unit(),
                        purchase.purchasedAt(),
                        purchase.expiresAt(),
                        purchase.expiryProvenance().name(),
                        purchase.referenceType(),
                        purchase.referenceId()
                ),
                java.util.Map.of("source", purchase.source())
        ));

        return new StockLot(
                lotId,
                purchase.ingredientId(),
                canonical.quantity(),
                canonical.unit(),
                purchase.purchasedAt(),
                purchase.expiresAt(),
                purchase.source()
        );
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required.");
        }
    }

    private ZoneId kitchenZone(String kitchenId) {
        String timezone = jdbc.sql("""
                        SELECT timezone
                        FROM kitchens
                        WHERE id = :kitchen
                          AND active = TRUE
                        """)
                .param("kitchen", kitchenId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Active kitchen not found: " + kitchenId
                ));
        return ZoneId.of(timezone);
    }

    private record PurchaseEventPayload(
            String lotId,
            String locationId,
            String ingredientId,
            BigDecimal quantity,
            String unit,
            LocalDate purchasedAt,
            LocalDate expiresAt,
            String expiryProvenance,
            String referenceType,
            String referenceId
    ) {
    }

    public record Purchase(
            String lotId,
            String kitchenId,
            String locationId,
            String ingredientId,
            BigDecimal quantity,
            String unit,
            BigDecimal sourceQuantity,
            String sourceUnit,
            LocalDate purchasedAt,
            LocalDate expiresAt,
            ExpiryProvenance expiryProvenance,
            String source,
            String referenceType,
            String referenceId,
            String receiptId,
            String receiptItemId,
            Instant occurredAt
    ) {
    }
}
