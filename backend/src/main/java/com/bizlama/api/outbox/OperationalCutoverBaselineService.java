package com.bizlama.api.outbox;

import com.bizlama.api.config.JdbcTimestamp;
import com.bizlama.api.domain.Ingredient;
import com.bizlama.api.quantity.CanonicalQuantity;
import com.bizlama.api.quantity.UnitConversionService;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Converts the immutable V16 cutover snapshot and V18 scope manifests into
 * ordinary outbox events.
 *
 * <p>The snapshot is captured by Flyway before the upgraded application accepts
 * traffic. Each row is locked, converted with the same deterministic unit rules
 * as live operations, and linked to its outbox event in one transaction. A
 * legacy value which cannot be converted is retained as {@code REVIEW_REQUIRED}
 * and never guessed. A scope manifest is emitted only after all of that scope's
 * staged rows are durably linked to the outbox.</p>
 */
@Service
public class OperationalCutoverBaselineService {

    private static final String CUTOVER_ID =
            "outbox-v16-operational-baseline";
    private static final int MAX_ERROR_LENGTH = 1000;
    private static final MathContext CALCULATION_CONTEXT =
            MathContext.DECIMAL128;

    private final JdbcClient jdbc;
    private final UnitConversionService units;
    private final TransactionalOutboxService outbox;
    private final OutboxProperties properties;
    private final Clock clock;

    public OperationalCutoverBaselineService(
            JdbcClient jdbc,
            UnitConversionService units,
            TransactionalOutboxService outbox,
            OutboxProperties properties,
            Clock clock
    ) {
        this.jdbc = jdbc;
        this.units = units;
        this.outbox = outbox;
        this.properties = properties;
        this.clock = clock;
    }

    /** Emits at most the configured outbox batch size. Repeated calls are safe. */
    @Transactional
    public DrainResult drainBatch() {
        requeueTrustedOrderDemand();
        int remaining = properties.batchSize();
        int emitted = 0;
        int reviewRequired = 0;

        List<InventoryBaseline> inventory = lockInventory(remaining);
        for (InventoryBaseline row : inventory) {
            try {
                emitInventory(row);
                emitted++;
            } catch (IllegalArgumentException error) {
                markInventoryForReview(row, error);
                reviewRequired++;
            }
        }
        remaining -= inventory.size();

        if (remaining > 0) {
            List<OrderDemandBaseline> demand = lockOrderDemand(remaining);
            for (OrderDemandBaseline row : demand) {
                try {
                    emitOrderDemand(row);
                    emitted++;
                } catch (IllegalArgumentException error) {
                    markOrderDemandForReview(row, error);
                    reviewRequired++;
                }
            }
            remaining -= demand.size();
        }

        synchronizeScopeManifestReviews();
        if (remaining > 0) {
            List<ScopeManifest> manifests = lockReadyScopeManifests(remaining);
            for (ScopeManifest manifest : manifests) {
                try {
                    emitScopeManifest(manifest);
                    emitted++;
                } catch (IllegalArgumentException error) {
                    markScopeManifestForReview(manifest, error);
                    reviewRequired++;
                }
            }
        }

        refreshRunState();
        return new DrainResult(emitted, reviewRequired);
    }

    private List<InventoryBaseline> lockInventory(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT baseline.cutover_id, baseline.lot_id,
                               baseline.kitchen_id, baseline.location_id,
                               baseline.ingredient_id,
                               baseline.quantity_remaining,
                               baseline.stored_unit, baseline.purchased_at,
                               baseline.expires_at,
                               baseline.expiry_provenance,
                               baseline.lot_status, baseline.captured_at,
                               ingredient.name AS ingredient_name,
                               ingredient.base_unit AS ingredient_base_unit
                        FROM analytics_inventory_cutover_baseline baseline
                        JOIN ingredients ingredient
                          ON ingredient.id = baseline.ingredient_id
                         AND ingredient.kitchen_id = baseline.kitchen_id
                        WHERE baseline.cutover_id = :cutover
                          AND baseline.emission_state = 'PENDING'
                        ORDER BY baseline.kitchen_id, baseline.location_id,
                                 baseline.lot_id
                        LIMIT :limit
                        FOR UPDATE SKIP LOCKED
                        """)
                .param("cutover", CUTOVER_ID)
                .param("limit", limit)
                .query(OperationalCutoverBaselineService::inventoryBaseline)
                .list();
    }

    private List<OrderDemandBaseline> lockOrderDemand(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT baseline.cutover_id, baseline.order_id,
                               baseline.line_number, baseline.ingredient_id,
                               baseline.kitchen_id, baseline.location_id,
                               baseline.dish_id, baseline.recipe_version_id,
                               baseline.ordered_quantity,
                               baseline.recipe_ingredient_quantity,
                               baseline.recipe_ingredient_unit,
                               baseline.recipe_yield_quantity,
                               baseline.recipe_yield_unit,
                               baseline.ingredient_name,
                               baseline.ingredient_base_unit,
                               baseline.order_occurred_at,
                               baseline.required_at, baseline.captured_at,
                               item.recipe_pin_provenance,
                               recipe.yield_provenance
                        FROM analytics_order_demand_cutover_baseline baseline
                        JOIN order_items item
                          ON item.order_id = baseline.order_id
                         AND item.line_number = baseline.line_number
                        JOIN recipe_versions recipe
                          ON recipe.id = baseline.recipe_version_id
                         AND recipe.kitchen_id = baseline.kitchen_id
                        WHERE baseline.cutover_id = :cutover
                          AND baseline.emission_state = 'PENDING'
                        ORDER BY baseline.kitchen_id, baseline.location_id,
                                 baseline.order_id, baseline.line_number,
                                 baseline.ingredient_id
                        LIMIT :limit
                        FOR UPDATE SKIP LOCKED
                        """)
                .param("cutover", CUTOVER_ID)
                .param("limit", limit)
                .query(OperationalCutoverBaselineService::orderDemandBaseline)
                .list();
    }

    private List<ScopeManifest> lockReadyScopeManifests(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT manifest.cutover_id, manifest.kitchen_id,
                               manifest.location_id, manifest.captured_at,
                               manifest.expected_inventory_lot_count,
                               manifest.expected_order_demand_row_count,
                               (
                                 SELECT COUNT(*)
                                 FROM analytics_inventory_cutover_baseline inventory
                                 WHERE inventory.cutover_id = manifest.cutover_id
                                   AND inventory.kitchen_id = manifest.kitchen_id
                                   AND inventory.location_id = manifest.location_id
                               ) AS actual_inventory_lot_count,
                               (
                                 SELECT COUNT(*)
                                 FROM analytics_order_demand_cutover_baseline demand
                                 WHERE demand.cutover_id = manifest.cutover_id
                                   AND demand.kitchen_id = manifest.kitchen_id
                                   AND demand.location_id = manifest.location_id
                               ) AS actual_order_demand_row_count
                        FROM analytics_cutover_scope_manifests manifest
                        WHERE manifest.cutover_id = :cutover
                          AND manifest.emission_state = 'PENDING'
                          AND NOT EXISTS (
                            SELECT 1
                            FROM analytics_inventory_cutover_baseline inventory
                            WHERE inventory.cutover_id = manifest.cutover_id
                              AND inventory.kitchen_id = manifest.kitchen_id
                              AND inventory.location_id = manifest.location_id
                              AND inventory.emission_state <> 'EMITTED'
                          )
                          AND NOT EXISTS (
                            SELECT 1
                            FROM analytics_order_demand_cutover_baseline demand
                            WHERE demand.cutover_id = manifest.cutover_id
                              AND demand.kitchen_id = manifest.kitchen_id
                              AND demand.location_id = manifest.location_id
                              AND demand.emission_state <> 'EMITTED'
                          )
                        ORDER BY manifest.kitchen_id, manifest.location_id
                        LIMIT :limit
                        FOR UPDATE SKIP LOCKED
                        """)
                .param("cutover", CUTOVER_ID)
                .param("limit", limit)
                .query(OperationalCutoverBaselineService::scopeManifest)
                .list();
    }

    private void emitInventory(InventoryBaseline row) {
        Ingredient ingredient = new Ingredient(
                row.ingredientId(),
                row.ingredientName(),
                row.ingredientBaseUnit(),
                true
        );
        CanonicalQuantity canonical = units.toIngredientBase(
                ingredient,
                row.quantityRemaining(),
                row.storedUnit()
        );
        String deduplicationKey = "inventory-balance-baseline:"
                + row.cutoverId() + ":" + row.lotId();
        requireLength(row.lotId(), 120, "Inventory baseline entity ID");
        requireLength(deduplicationKey, 200,
                "Inventory baseline deduplication key");

        InventoryBaselinePayload payload = new InventoryBaselinePayload(
                row.cutoverId(),
                row.locationId(),
                row.lotId(),
                row.ingredientId(),
                canonical.quantity(),
                canonical.unit(),
                row.quantityRemaining(),
                row.storedUnit(),
                row.purchasedAt(),
                row.expiresAt(),
                row.expiryProvenance(),
                row.lotStatus(),
                row.capturedAt()
        );
        OutboxEvent event = outbox.append(new OutboxEventDraft(
                null,
                "INVENTORY_BALANCE_BASELINED",
                1,
                row.kitchenId(),
                "stock_lot",
                row.lotId(),
                null,
                row.cutoverId(),
                null,
                deduplicationKey,
                payload,
                Map.of(
                        "component", "operational-cutover-baseline",
                        "locationId", row.locationId()
                )
        ));
        markEmitted(
                "analytics_inventory_cutover_baseline",
                row.cutoverId(),
                row.lotId(),
                null,
                null,
                event.eventId()
        );
    }

    private void emitOrderDemand(OrderDemandBaseline row) {
        if (!"CAPTURED_AT_ORDER".equals(row.recipePinProvenance())
                && !"LEGACY_UNAMBIGUOUS".equals(row.recipePinProvenance())
                && !"OWNER_CONFIRMED".equals(row.recipePinProvenance())) {
            throw new UnverifiedRecipePinException(
                    "Order demand requires a captured or owner-confirmed recipe pin; "
                            + "legacy reconstruction remains review-only."
            );
        }
        if (!"OPERATOR_ENTERED".equals(row.recipeYieldProvenance())
                && !"OWNER_CONFIRMED".equals(row.recipeYieldProvenance())) {
            throw new UnverifiedRecipeYieldException(
                    "Order demand requires an operator-entered or owner-confirmed recipe yield; legacy per-item yield remains review-only."
            );
        }
        Ingredient ingredient = new Ingredient(
                row.ingredientId(),
                row.ingredientName(),
                row.ingredientBaseUnit(),
                true
        );
        CanonicalQuantity recipeQuantity = units.toIngredientBase(
                ingredient,
                row.recipeIngredientQuantity(),
                row.recipeIngredientUnit()
        );
        CanonicalQuantity recipeYield = units.convert(
                row.recipeYieldQuantity(),
                row.recipeYieldUnit(),
                "each"
        );
        BigDecimal canonicalDemand = recipeQuantity.quantity()
                .multiply(
                        BigDecimal.valueOf(row.orderedQuantity()),
                        CALCULATION_CONTEXT
                )
                .divide(recipeYield.quantity(), CALCULATION_CONTEXT)
                .setScale(6, RoundingMode.HALF_UP)
                .stripTrailingZeros();

        String entityId = row.orderId() + ":" + row.lineNumber() + ":"
                + row.ingredientId();
        String deduplicationKey = "order-ingredient-demand:" + entityId;
        requireLength(entityId, 120, "Order-demand baseline entity ID");
        requireLength(deduplicationKey, 200,
                "Order-demand baseline deduplication key");

        OrderDemandBaselinePayload payload = new OrderDemandBaselinePayload(
                row.orderId(),
                row.lineNumber(),
                row.dishId(),
                row.recipeVersionId(),
                row.locationId(),
                row.orderedQuantity(),
                row.ingredientId(),
                row.recipeIngredientQuantity(),
                row.recipeIngredientUnit(),
                row.recipeYieldQuantity(),
                row.recipeYieldUnit(),
                canonicalDemand,
                recipeQuantity.unit(),
                row.requiredAt(),
                row.orderOccurredAt(),
                row.cutoverId()
        );
        OutboxEvent event = outbox.append(new OutboxEventDraft(
                null,
                "ORDER_INGREDIENT_DEMAND",
                1,
                row.kitchenId(),
                "order_ingredient_demand",
                entityId,
                null,
                row.orderId(),
                row.cutoverId(),
                deduplicationKey,
                payload,
                Map.of("component", "operational-cutover-baseline")
        ));
        markEmitted(
                "analytics_order_demand_cutover_baseline",
                row.cutoverId(),
                row.orderId(),
                row.lineNumber(),
                row.ingredientId(),
                event.eventId()
        );
    }

    private void emitScopeManifest(ScopeManifest manifest) {
        if (manifest.actualInventoryLotCount()
                != manifest.expectedInventoryLotCount()
                || manifest.actualOrderDemandRowCount()
                != manifest.expectedOrderDemandRowCount()) {
            throw new IllegalArgumentException(
                    "Cutover scope baseline counts changed after V18 capture."
            );
        }

        String entityId = manifest.locationId();
        String deduplicationKey = "cutover-scope-ready:"
                + manifest.cutoverId() + ":" + manifest.locationId();
        requireLength(entityId, 120, "Cutover scope entity ID");
        requireLength(deduplicationKey, 200,
                "Cutover scope deduplication key");
        Instant readyAt = clock.instant();
        ScopeManifestPayload payload = new ScopeManifestPayload(
                manifest.cutoverId(),
                manifest.locationId(),
                manifest.capturedAt(),
                manifest.expectedInventoryLotCount(),
                manifest.expectedOrderDemandRowCount(),
                readyAt
        );
        OutboxEvent event = outbox.append(new OutboxEventDraft(
                null,
                "OPERATIONAL_CUTOVER_SCOPE_READY",
                1,
                manifest.kitchenId(),
                "operational_cutover_scope",
                entityId,
                null,
                manifest.cutoverId(),
                null,
                deduplicationKey,
                payload,
                Map.of(
                        "component", "operational-cutover-baseline",
                        "locationId", manifest.locationId()
                )
        ));
        int changed = jdbc.sql("""
                        UPDATE analytics_cutover_scope_manifests
                        SET emission_state = 'EMITTED',
                            emission_attempts = emission_attempts + 1,
                            outbox_event_id = :event,
                            emitted_at = :emittedAt,
                            review_reason_code = NULL,
                            last_error = NULL
                        WHERE cutover_id = :cutover
                          AND kitchen_id = :kitchen
                          AND location_id = :location
                          AND emission_state = 'PENDING'
                        """)
                .param("event", event.eventId())
                .param("emittedAt", JdbcTimestamp.utc(readyAt))
                .param("cutover", manifest.cutoverId())
                .param("kitchen", manifest.kitchenId())
                .param("location", manifest.locationId())
                .update();
        if (changed != 1) {
            throw new IllegalStateException(
                    "Cutover scope manifest changed while it was emitted."
            );
        }
    }

    private void markEmitted(
            String table,
            String cutoverId,
            String entityId,
            Integer lineNumber,
            String ingredientId,
            String eventId
    ) {
        String identity = lineNumber == null
                ? "lot_id = :entity"
                : "order_id = :entity AND line_number = :line"
                        + " AND ingredient_id = :ingredient";
        JdbcClient.StatementSpec statement = jdbc.sql("""
                        UPDATE %s
                        SET emission_state = 'EMITTED',
                            emission_attempts = emission_attempts + 1,
                            outbox_event_id = :event,
                            emitted_at = :emittedAt,
                            review_reason_code = NULL,
                            last_error = NULL
                        WHERE cutover_id = :cutover
                          AND %s
                          AND emission_state = 'PENDING'
                        """.formatted(table, identity))
                .param("event", eventId)
                .param("emittedAt", JdbcTimestamp.utc(clock.instant()))
                .param("cutover", cutoverId)
                .param("entity", entityId);
        if (lineNumber != null) {
            statement = statement.param("line", lineNumber)
                    .param("ingredient", ingredientId);
        }
        if (statement.update() != 1) {
            throw new IllegalStateException(
                    "Cutover baseline row changed while it was emitted."
            );
        }
    }

    private void markInventoryForReview(
            InventoryBaseline row,
            RuntimeException error
    ) {
        markForReview(
                "analytics_inventory_cutover_baseline",
                "lot_id = :entity",
                Map.of("entity", row.lotId()),
                row.cutoverId(),
                error
        );
    }

    private void markOrderDemandForReview(
            OrderDemandBaseline row,
            RuntimeException error
    ) {
        Map<String, Object> keys = new LinkedHashMap<>();
        keys.put("entity", row.orderId());
        keys.put("line", row.lineNumber());
        keys.put("ingredient", row.ingredientId());
        markForReview(
                "analytics_order_demand_cutover_baseline",
                "order_id = :entity AND line_number = :line"
                        + " AND ingredient_id = :ingredient",
                keys,
                row.cutoverId(),
                error
        );
    }

    private void markScopeManifestForReview(
            ScopeManifest manifest,
            RuntimeException error
    ) {
        String detail = error.getMessage() == null
                ? error.getClass().getSimpleName()
                : error.getMessage();
        if (detail.length() > MAX_ERROR_LENGTH) {
            detail = detail.substring(0, MAX_ERROR_LENGTH);
        }
        jdbc.sql("""
                        UPDATE analytics_cutover_scope_manifests
                        SET emission_state = 'REVIEW_REQUIRED',
                            emission_attempts = emission_attempts + 1,
                            review_reason_code = 'BASELINE_COUNT_MISMATCH',
                            last_error = :error
                        WHERE cutover_id = :cutover
                          AND kitchen_id = :kitchen
                          AND location_id = :location
                          AND emission_state = 'PENDING'
                        """)
                .param("error", detail)
                .param("cutover", manifest.cutoverId())
                .param("kitchen", manifest.kitchenId())
                .param("location", manifest.locationId())
                .update();
    }

    private void markForReview(
            String table,
            String identity,
            Map<String, ?> keys,
            String cutoverId,
            RuntimeException error
    ) {
        String detail = error.getMessage() == null
                ? error.getClass().getSimpleName()
                : error.getMessage();
        if (detail.length() > MAX_ERROR_LENGTH) {
            detail = detail.substring(0, MAX_ERROR_LENGTH);
        }
        jdbc.sql("""
                        UPDATE %s
                        SET emission_state = 'REVIEW_REQUIRED',
                            emission_attempts = emission_attempts + 1,
                            review_reason_code = :reasonCode,
                            last_error = :error
                        WHERE cutover_id = :cutover
                          AND %s
                          AND emission_state = 'PENDING'
                        """.formatted(table, identity))
                .param("error", detail)
                .param("reasonCode", reviewReasonCode(error))
                .param("cutover", cutoverId)
                .params(keys)
                .update();
    }

    private void requeueTrustedOrderDemand() {
        jdbc.sql("""
                        UPDATE analytics_order_demand_cutover_baseline baseline
                        SET emission_state = 'PENDING',
                            outbox_event_id = NULL,
                            emitted_at = NULL,
                            review_reason_code = NULL,
                            last_error = NULL
                        WHERE baseline.cutover_id = :cutover
                          AND baseline.emission_state = 'REVIEW_REQUIRED'
                          AND (
                            (
                              baseline.review_reason_code = 'LEGACY_RECIPE_PIN'
                              AND EXISTS (
                                SELECT 1
                                FROM order_items item
                                WHERE item.order_id = baseline.order_id
                                  AND item.line_number = baseline.line_number
                                  AND item.recipe_pin_provenance = 'OWNER_CONFIRMED'
                              )
                            )
                            OR
                            (
                              baseline.review_reason_code = 'LEGACY_RECIPE_YIELD'
                              AND EXISTS (
                                SELECT 1
                                FROM recipe_versions recipe
                                WHERE recipe.id = baseline.recipe_version_id
                                  AND recipe.kitchen_id = baseline.kitchen_id
                                  AND recipe.yield_provenance IN (
                                    'OPERATOR_ENTERED', 'OWNER_CONFIRMED'
                                  )
                              )
                            )
                          )
                        """)
                .param("cutover", CUTOVER_ID)
                .update();
    }

    private void synchronizeScopeManifestReviews() {
        jdbc.sql("""
                        UPDATE analytics_cutover_scope_manifests manifest
                        SET emission_state = 'PENDING',
                            review_reason_code = NULL,
                            last_error = NULL
                        WHERE manifest.cutover_id = :cutover
                          AND manifest.emission_state = 'REVIEW_REQUIRED'
                          AND manifest.review_reason_code =
                              'BASELINE_REVIEW_REQUIRED'
                          AND NOT EXISTS (
                            SELECT 1
                            FROM analytics_inventory_cutover_baseline inventory
                            WHERE inventory.cutover_id = manifest.cutover_id
                              AND inventory.kitchen_id = manifest.kitchen_id
                              AND inventory.location_id = manifest.location_id
                              AND inventory.emission_state = 'REVIEW_REQUIRED'
                          )
                          AND NOT EXISTS (
                            SELECT 1
                            FROM analytics_order_demand_cutover_baseline demand
                            WHERE demand.cutover_id = manifest.cutover_id
                              AND demand.kitchen_id = manifest.kitchen_id
                              AND demand.location_id = manifest.location_id
                              AND demand.emission_state = 'REVIEW_REQUIRED'
                          )
                        """)
                .param("cutover", CUTOVER_ID)
                .update();

        jdbc.sql("""
                        UPDATE analytics_cutover_scope_manifests manifest
                        SET emission_state = 'REVIEW_REQUIRED',
                            review_reason_code = 'BASELINE_REVIEW_REQUIRED',
                            last_error =
                              'One or more scope baseline rows require review.'
                        WHERE manifest.cutover_id = :cutover
                          AND manifest.emission_state = 'PENDING'
                          AND (
                            EXISTS (
                              SELECT 1
                              FROM analytics_inventory_cutover_baseline inventory
                              WHERE inventory.cutover_id = manifest.cutover_id
                                AND inventory.kitchen_id = manifest.kitchen_id
                                AND inventory.location_id = manifest.location_id
                                AND inventory.emission_state = 'REVIEW_REQUIRED'
                            )
                            OR EXISTS (
                              SELECT 1
                              FROM analytics_order_demand_cutover_baseline demand
                              WHERE demand.cutover_id = manifest.cutover_id
                                AND demand.kitchen_id = manifest.kitchen_id
                                AND demand.location_id = manifest.location_id
                                AND demand.emission_state = 'REVIEW_REQUIRED'
                            )
                          )
                        """)
                .param("cutover", CUTOVER_ID)
                .update();
    }

    private void refreshRunState() {
        long pending = stateCount("PENDING");
        long reviews = stateCount("REVIEW_REQUIRED");
        String state;
        String error;
        Instant readyAt;
        if (pending > 0) {
            state = "PENDING";
            error = null;
            readyAt = null;
        } else if (reviews > 0) {
            state = "REVIEW_REQUIRED";
            error = reviews
                    + " cutover baseline row(s) require explicit review.";
            readyAt = null;
        } else {
            state = "OUTBOX_READY";
            error = null;
            readyAt = clock.instant();
        }

        jdbc.sql("""
                        UPDATE analytics_cutover_runs
                        SET state = :state,
                            outbox_ready_at = :readyAt,
                            last_error = :error
                        WHERE id = :cutover
                        """)
                .param("state", state)
                .param("readyAt", JdbcTimestamp.utc(readyAt))
                .param("error", error)
                .param("cutover", CUTOVER_ID)
                .update();
    }

    private long stateCount(String state) {
        return jdbc.sql("""
                        SELECT
                          (SELECT COUNT(*)
                           FROM analytics_inventory_cutover_baseline
                           WHERE cutover_id = :cutover
                             AND emission_state = :state)
                          +
                          (SELECT COUNT(*)
                           FROM analytics_order_demand_cutover_baseline
                           WHERE cutover_id = :cutover
                             AND emission_state = :state)
                          +
                          (SELECT COUNT(*)
                           FROM analytics_cutover_scope_manifests
                           WHERE cutover_id = :cutover
                             AND emission_state = :state)
                        """)
                .param("cutover", CUTOVER_ID)
                .param("state", state)
                .query(Long.class)
                .single();
    }

    private static String reviewReasonCode(RuntimeException error) {
        if (error instanceof UnverifiedRecipePinException) {
            return "LEGACY_RECIPE_PIN";
        }
        if (error instanceof UnverifiedRecipeYieldException) {
            return "LEGACY_RECIPE_YIELD";
        }
        return "DATA_CONTRACT_ERROR";
    }

    private static void requireLength(String value, int maximum, String label) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException(
                    label + " must contain 1 to " + maximum + " characters."
            );
        }
    }

    private static InventoryBaseline inventoryBaseline(
            ResultSet result,
            int rowNumber
    ) throws SQLException {
        return new InventoryBaseline(
                result.getString("cutover_id"),
                result.getString("lot_id"),
                result.getString("kitchen_id"),
                result.getString("location_id"),
                result.getString("ingredient_id"),
                result.getBigDecimal("quantity_remaining"),
                result.getString("stored_unit"),
                result.getObject("purchased_at", LocalDate.class),
                result.getObject("expires_at", LocalDate.class),
                result.getString("expiry_provenance"),
                result.getString("lot_status"),
                instant(result, "captured_at"),
                result.getString("ingredient_name"),
                result.getString("ingredient_base_unit")
        );
    }

    private static OrderDemandBaseline orderDemandBaseline(
            ResultSet result,
            int rowNumber
    ) throws SQLException {
        return new OrderDemandBaseline(
                result.getString("cutover_id"),
                result.getString("order_id"),
                result.getInt("line_number"),
                result.getString("ingredient_id"),
                result.getString("kitchen_id"),
                result.getString("location_id"),
                result.getString("dish_id"),
                result.getString("recipe_version_id"),
                result.getInt("ordered_quantity"),
                result.getBigDecimal("recipe_ingredient_quantity"),
                result.getString("recipe_ingredient_unit"),
                result.getBigDecimal("recipe_yield_quantity"),
                result.getString("recipe_yield_unit"),
                result.getString("ingredient_name"),
                result.getString("ingredient_base_unit"),
                instant(result, "order_occurred_at"),
                instant(result, "required_at"),
                instant(result, "captured_at"),
                result.getString("recipe_pin_provenance"),
                result.getString("yield_provenance")
        );
    }

    private static ScopeManifest scopeManifest(
            ResultSet result,
            int rowNumber
    ) throws SQLException {
        return new ScopeManifest(
                result.getString("cutover_id"),
                result.getString("kitchen_id"),
                result.getString("location_id"),
                instant(result, "captured_at"),
                result.getLong("expected_inventory_lot_count"),
                result.getLong("expected_order_demand_row_count"),
                result.getLong("actual_inventory_lot_count"),
                result.getLong("actual_order_demand_row_count")
        );
    }

    private static Instant instant(ResultSet result, String column)
            throws SQLException {
        return result.getObject(column, OffsetDateTime.class).toInstant();
    }

    public record DrainResult(int emitted, int reviewRequired) {
    }

    private static final class UnverifiedRecipePinException
            extends IllegalArgumentException {

        private UnverifiedRecipePinException(String message) {
            super(message);
        }
    }

    private static final class UnverifiedRecipeYieldException
            extends IllegalArgumentException {

        private UnverifiedRecipeYieldException(String message) {
            super(message);
        }
    }

    private record InventoryBaseline(
            String cutoverId,
            String lotId,
            String kitchenId,
            String locationId,
            String ingredientId,
            BigDecimal quantityRemaining,
            String storedUnit,
            LocalDate purchasedAt,
            LocalDate expiresAt,
            String expiryProvenance,
            String lotStatus,
            Instant capturedAt,
            String ingredientName,
            String ingredientBaseUnit
    ) {
    }

    private record OrderDemandBaseline(
            String cutoverId,
            String orderId,
            int lineNumber,
            String ingredientId,
            String kitchenId,
            String locationId,
            String dishId,
            String recipeVersionId,
            int orderedQuantity,
            BigDecimal recipeIngredientQuantity,
            String recipeIngredientUnit,
            BigDecimal recipeYieldQuantity,
            String recipeYieldUnit,
            String ingredientName,
            String ingredientBaseUnit,
            Instant orderOccurredAt,
            Instant requiredAt,
            Instant capturedAt,
            String recipePinProvenance,
            String recipeYieldProvenance
    ) {
    }

    private record ScopeManifest(
            String cutoverId,
            String kitchenId,
            String locationId,
            Instant capturedAt,
            long expectedInventoryLotCount,
            long expectedOrderDemandRowCount,
            long actualInventoryLotCount,
            long actualOrderDemandRowCount
    ) {
    }

    private record InventoryBaselinePayload(
            String cutoverId,
            String locationId,
            String lotId,
            String ingredientId,
            BigDecimal quantity,
            String unit,
            BigDecimal sourceQuantity,
            String sourceUnit,
            LocalDate purchasedAt,
            LocalDate expiresAt,
            String expiryProvenance,
            String lotStatus,
            Instant capturedAt
    ) {
    }

    private record OrderDemandBaselinePayload(
            String orderId,
            int lineNumber,
            String dishId,
            String recipeVersionId,
            String locationId,
            int orderedQuantity,
            String ingredientId,
            BigDecimal recipeIngredientQuantity,
            String recipeIngredientUnit,
            BigDecimal recipeYieldQuantity,
            String recipeYieldUnit,
            BigDecimal canonicalDemand,
            String canonicalUnit,
            Instant requiredAt,
            Instant orderOccurredAt,
            String cutoverId
    ) {
    }

    private record ScopeManifestPayload(
            String cutoverId,
            String locationId,
            Instant capturedAt,
            long expectedInventoryLotCount,
            long expectedOrderDemandRowCount,
            Instant scopeReadyAt
    ) {
    }
}
