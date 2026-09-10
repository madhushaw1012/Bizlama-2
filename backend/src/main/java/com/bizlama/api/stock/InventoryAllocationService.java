package com.bizlama.api.stock;

import com.bizlama.api.config.JdbcTimestamp;
import com.bizlama.api.domain.Ingredient;
import com.bizlama.api.domain.StockMovement;
import com.bizlama.api.quantity.CanonicalQuantity;
import com.bizlama.api.quantity.UnitConversionService;
import com.bizlama.api.outbox.OutboxEventDraft;
import com.bizlama.api.outbox.TransactionalOutboxService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PostgreSQL-safe first-expiry-first-out allocation.
 *
 * <p>The service locks all eligible lots in deterministic FEFO order before it
 * checks sufficiency. An insufficient request throws inside the transaction, so
 * no deduction or movement can commit. Conditional version/quantity checks are
 * retained as a second line of defence.</p>
 */
@Service
public class InventoryAllocationService {

    private final JdbcClient jdbc;
    private final UnitConversionService units;
    private final TransactionalOutboxService outbox;

    public InventoryAllocationService(
            JdbcClient jdbc,
            UnitConversionService units,
            TransactionalOutboxService outbox
    ) {
        this.jdbc = jdbc;
        this.units = units;
        this.outbox = outbox;
    }

    @Transactional
    public AllocationResult allocate(
            String kitchenId,
            String locationId,
            String ingredientId,
            BigDecimal requestedQuantity,
            String requestedUnit,
            Instant actionAt,
            StockMovement.MovementType movementType,
            String referenceType,
            String referenceId
    ) {
        requireText(kitchenId, "Kitchen");
        requireText(locationId, "Location");
        requireText(ingredientId, "Ingredient");
        requireText(referenceType, "Reference type");
        requireText(referenceId, "Reference ID");
        Objects.requireNonNull(actionAt, "Action time is required.");
        Objects.requireNonNull(movementType, "Movement type is required.");
        if (requestedQuantity == null || requestedQuantity.signum() <= 0) {
            throw new IllegalArgumentException("Requested quantity must be positive.");
        }

        Ingredient ingredient = jdbc.sql("""
                        SELECT id, name, base_unit, active
                        FROM ingredients
                        WHERE id = :ingredient
                          AND kitchen_id = :kitchen
                          AND active = TRUE
                        """)
                .param("ingredient", ingredientId)
                .param("kitchen", kitchenId)
                .query((rs, row) -> new Ingredient(
                        rs.getString("id"),
                        rs.getString("name"),
                        rs.getString("base_unit"),
                        rs.getBoolean("active")
                ))
                .optional()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Active ingredient not found in kitchen: " + ingredientId
                ));

        CanonicalQuantity requested = units.toIngredientBase(
                ingredient,
                requestedQuantity,
                requestedUnit
        );
        LocalDate actionDate = actionAt.atZone(kitchenZone(kitchenId)).toLocalDate();

        List<LockedLot> lots = jdbc.sql("""
                        SELECT id, quantity_remaining, unit, expires_at, version
                        FROM stock_lots
                        WHERE kitchen_id = :kitchen
                          AND location_id = :location
                          AND ingredient_id = :ingredient
                          AND status = 'AVAILABLE'
                          AND quantity_remaining > 0
                          AND purchased_at <= :actionDate
                          AND expires_at >= :actionDate
                        ORDER BY expires_at, purchased_at, id
                        FOR UPDATE
                        """)
                .param("kitchen", kitchenId)
                .param("location", locationId)
                .param("ingredient", ingredientId)
                .param("actionDate", actionDate)
                .query((rs, row) -> new LockedLot(
                        rs.getString("id"),
                        rs.getBigDecimal("quantity_remaining"),
                        rs.getString("unit"),
                        rs.getObject("expires_at", LocalDate.class),
                        rs.getLong("version")
                ))
                .list();

        BigDecimal usable = lots.stream()
                .map(lot -> units.toIngredientBase(
                        ingredient,
                        lot.quantityRemaining(),
                        lot.unit()
                ).quantity())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (usable.compareTo(requested.quantity()) < 0) {
            throw new InsufficientStockException(
                    ingredientId,
                    requested.quantity(),
                    usable,
                    requested.unit()
            );
        }

        BigDecimal remaining = requested.quantity();
        List<LotAllocation> allocations = new ArrayList<>();

        for (LockedLot lot : lots) {
            if (remaining.signum() == 0) {
                break;
            }

            BigDecimal lotCanonical = units.toIngredientBase(
                    ingredient,
                    lot.quantityRemaining(),
                    lot.unit()
            ).quantity();
            BigDecimal usedCanonical = remaining.min(lotCanonical);
            BigDecimal usedStored = units.convert(
                    usedCanonical,
                    requested.unit(),
                    lot.unit()
            ).quantity();

            int changed = jdbc.sql("""
                            UPDATE stock_lots
                            SET quantity_remaining = quantity_remaining - :used,
                                status = CASE
                                    WHEN quantity_remaining - :used = 0
                                    THEN 'DEPLETED'
                                    ELSE status
                                END,
                                version = version + 1
                            WHERE id = :id
                              AND kitchen_id = :kitchen
                              AND location_id = :location
                              AND ingredient_id = :ingredient
                              AND version = :version
                              AND status = 'AVAILABLE'
                              AND purchased_at <= :actionDate
                              AND quantity_remaining >= :used
                            """)
                    .param("used", usedStored)
                    .param("id", lot.id())
                    .param("kitchen", kitchenId)
                    .param("location", locationId)
                    .param("ingredient", ingredientId)
                    .param("version", lot.version())
                    .param("actionDate", actionDate)
                    .update();

            if (changed != 1) {
                throw new IllegalStateException(
                        "Stock changed during allocation; retry from fresh inventory."
                );
            }

            String movementId = UUID.randomUUID().toString();
            jdbc.sql("""
                            INSERT INTO stock_movements
                            (id, stock_lot_id, ingredient_id, movement_type,
                             quantity_change, unit, reference_type, reference_id,
                             occurred_at, kitchen_id, location_id)
                            VALUES
                            (:id, :lot, :ingredient, :type,
                             :quantity, :unit, :referenceType, :referenceId,
                             :occurredAt, :kitchen, :location)
                            """)
                    .param("id", movementId)
                    .param("lot", lot.id())
                    .param("ingredient", ingredientId)
                    .param("type", movementType.name())
                    .param("quantity", usedCanonical.negate())
                    .param("unit", requested.unit())
                    .param("referenceType", referenceType)
                    .param("referenceId", referenceId)
                    .param("occurredAt", JdbcTimestamp.utc(actionAt))
                    .param("kitchen", kitchenId)
                    .param("location", locationId)
                    .update();

            allocations.add(new LotAllocation(
                    lot.id(),
                    usedCanonical,
                    requested.unit(),
                    lot.expiresAt(),
                    movementId
            ));
            remaining = remaining.subtract(usedCanonical);
        }

        AllocationResult result = new AllocationResult(
                ingredientId,
                requested.quantity(),
                requested.unit(),
                actionAt,
                List.copyOf(allocations)
        );
        outbox.append(new OutboxEventDraft(
                null,
                eventType(movementType),
                1,
                kitchenId,
                "ingredient",
                ingredientId,
                actionAt,
                referenceId,
                null,
                "allocation:" + movementType.name() + ":"
                        + referenceType + ":" + referenceId + ":" + ingredientId,
                result,
                Map.of(
                        "locationId", locationId,
                        "referenceType", referenceType
                )
        ));
        return result;
    }

    private String eventType(StockMovement.MovementType movementType) {
        return switch (movementType) {
            case WASTE -> "WASTE_RECORDED";
            case PRODUCTION_CONSUMPTION -> "INVENTORY_CONSUMED";
            case EXPIRY_WRITE_OFF -> "INVENTORY_EXPIRED";
            case REVERSAL -> "INVENTORY_REVERSAL";
            case CORRECTION, MANUAL_ADJUSTMENT -> "INVENTORY_CORRECTED";
            case PURCHASE -> throw new IllegalArgumentException(
                    "Purchases must use InventoryPurchaseService."
            );
        };
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

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required.");
        }
    }

    private record LockedLot(
            String id,
            BigDecimal quantityRemaining,
            String unit,
            LocalDate expiresAt,
            long version
    ) {
    }

    public record LotAllocation(
            String lotId,
            BigDecimal quantity,
            String unit,
            LocalDate expiresAt,
            String movementId
    ) {
    }

    public record AllocationResult(
            String ingredientId,
            BigDecimal quantity,
            String unit,
            Instant actionAt,
            List<LotAllocation> lots
    ) {
    }
}
