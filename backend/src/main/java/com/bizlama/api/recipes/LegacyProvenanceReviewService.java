package com.bizlama.api.recipes;

import com.bizlama.api.config.JdbcTimestamp;
import com.bizlama.api.config.WorkspaceProperties;
import com.bizlama.api.outbox.OutboxEventDraft;
import com.bizlama.api.outbox.TransactionalOutboxService;
import com.bizlama.api.quantity.CanonicalQuantity;
import com.bizlama.api.quantity.UnitConversionService;
import com.bizlama.api.recommendations.DemandValidationException;
import com.bizlama.api.recommendations.RecommendationConflictException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owner-attested recovery for legacy values whose original schema retained
 * insufficient provenance. Reviews affirm existing evidence; changed recipes or
 * orders must use the normal version/cancel-and-recreate workflows.
 */
@Service
public class LegacyProvenanceReviewService {

    private static final String OWNER_CONFIRMED = "OWNER_CONFIRMED";
    private final JdbcClient jdbc;
    private final WorkspaceProperties workspace;
    private final UnitConversionService units;
    private final TransactionalOutboxService outbox;

    public LegacyProvenanceReviewService(
            JdbcClient jdbc,
            WorkspaceProperties workspace,
            UnitConversionService units,
            TransactionalOutboxService outbox
    ) {
        this.jdbc = jdbc;
        this.workspace = workspace;
        this.units = units;
        this.outbox = outbox;
    }

    @Transactional(readOnly = true)
    public ReviewQueue reviewQueue() {
        List<RecipeYieldReview> yields = jdbc.sql("""
                        SELECT recipe.id,
                               recipe.dish_id,
                               dish.name AS dish_name,
                               recipe.yield_quantity,
                               recipe.yield_unit,
                               recipe.yield_provenance
                        FROM recipe_versions recipe
                        JOIN dishes dish
                          ON dish.id = recipe.dish_id
                         AND dish.kitchen_id = recipe.kitchen_id
                        WHERE recipe.kitchen_id = :kitchen
                          AND recipe.yield_provenance = 'LEGACY_PER_ITEM_SCHEMA'
                        ORDER BY dish.name, recipe.version_number, recipe.id
                        """)
                .param("kitchen", workspace.kitchenId())
                .query((rs, row) -> new RecipeYieldReview(
                        rs.getString("id"),
                        rs.getString("dish_id"),
                        rs.getString("dish_name"),
                        rs.getBigDecimal("yield_quantity"),
                        rs.getString("yield_unit"),
                        rs.getString("yield_provenance")
                ))
                .list();

        List<OrderRecipePinReview> orders = jdbc.sql("""
                        SELECT item.order_id,
                               item.line_number,
                               item.dish_id,
                               dish.name AS dish_name,
                               item.recipe_version_id,
                               item.recipe_pin_provenance,
                               item.quantity,
                               item.prepared_quantity,
                               orders.created_at,
                               orders.required_at
                        FROM order_items item
                        JOIN customer_orders orders ON orders.id = item.order_id
                        JOIN dishes dish
                          ON dish.id = item.dish_id
                         AND dish.kitchen_id = orders.kitchen_id
                        WHERE orders.kitchen_id = :kitchen
                          AND orders.location_id = :location
                          AND item.recipe_pin_provenance = 'LEGACY_RECONSTRUCTED'
                        ORDER BY orders.created_at, item.order_id, item.line_number
                        """)
                .param("kitchen", workspace.kitchenId())
                .param("location", workspace.locationId())
                .query((rs, row) -> new OrderRecipePinReview(
                        rs.getString("order_id"),
                        rs.getInt("line_number"),
                        rs.getString("dish_id"),
                        rs.getString("dish_name"),
                        rs.getString("recipe_version_id"),
                        rs.getString("recipe_pin_provenance"),
                        rs.getInt("quantity"),
                        rs.getInt("prepared_quantity"),
                        instant(rs.getObject("created_at", OffsetDateTime.class)),
                        instant(rs.getObject("required_at", OffsetDateTime.class))
                ))
                .list();

        return new ReviewQueue(yields, orders);
    }

    @Transactional
    public ConfirmationResult confirmLegacyYield(
            String recipeVersionId,
            BigDecimal attestedQuantity,
            String attestedUnit,
            String reason,
            String actor
    ) {
        requireText(recipeVersionId, "Recipe version");
        requireText(attestedUnit, "Attested unit");
        requireText(reason, "Review reason");
        requireText(actor, "Reviewer");
        if (attestedQuantity == null || attestedQuantity.signum() <= 0) {
            throw new DemandValidationException(
                    "Attested recipe yield must be positive."
            );
        }

        YieldRow current = jdbc.sql("""
                        SELECT yield_quantity, yield_unit, yield_provenance
                        FROM recipe_versions
                        WHERE id = :recipe
                          AND kitchen_id = :kitchen
                        FOR UPDATE
                        """)
                .param("recipe", recipeVersionId)
                .param("kitchen", workspace.kitchenId())
                .query((rs, row) -> new YieldRow(
                        rs.getBigDecimal("yield_quantity"),
                        rs.getString("yield_unit"),
                        rs.getString("yield_provenance")
                ))
                .optional()
                .orElseThrow(() -> new DemandValidationException(
                        "Recipe version was not found in this kitchen."
                ));

        CanonicalQuantity stored = units.convert(
                current.quantity(),
                current.unit(),
                "each"
        );
        CanonicalQuantity attested = units.convert(
                attestedQuantity,
                attestedUnit,
                "each"
        );
        if (stored.quantity().compareTo(attested.quantity()) != 0) {
            throw new DemandValidationException(
                    "The attestation must match the stored legacy yield. "
                            + "Create and approve a new recipe version to change yield."
            );
        }
        if (OWNER_CONFIRMED.equals(current.provenance())) {
            return new ConfirmationResult(
                    "RECIPE_YIELD",
                    recipeVersionId,
                    null,
                    null,
                    OWNER_CONFIRMED,
                    false,
                    null
            );
        }
        if (!"LEGACY_PER_ITEM_SCHEMA".equals(current.provenance())) {
            throw new DemandValidationException(
                    "This recipe yield does not require legacy review."
            );
        }

        Instant now = Instant.now();
        int changed = jdbc.sql("""
                        UPDATE recipe_versions
                        SET yield_provenance = 'OWNER_CONFIRMED'
                        WHERE id = :recipe
                          AND kitchen_id = :kitchen
                          AND yield_provenance = 'LEGACY_PER_ITEM_SCHEMA'
                        """)
                .param("recipe", recipeVersionId)
                .param("kitchen", workspace.kitchenId())
                .update();
        requireSingleChange(changed);

        String reviewId = UUID.randomUUID().toString();
        jdbc.sql("""
                        INSERT INTO legacy_provenance_reviews
                        (id, review_type, kitchen_id, location_id,
                         recipe_version_id, previous_provenance,
                         resulting_provenance, attested_quantity, attested_unit,
                         reason, reviewed_by, reviewed_at)
                        VALUES
                        (:id, 'RECIPE_YIELD', :kitchen, :location,
                         :recipe, :previous, 'OWNER_CONFIRMED',
                         :quantity, :unit, :reason, :actor, :reviewedAt)
                        """)
                .param("id", reviewId)
                .param("kitchen", workspace.kitchenId())
                .param("location", workspace.locationId())
                .param("recipe", recipeVersionId)
                .param("previous", current.provenance())
                .param("quantity", attested.quantity())
                .param("unit", attested.unit())
                .param("reason", reason.trim())
                .param("actor", actor.trim())
                .param("reviewedAt", JdbcTimestamp.utc(now))
                .update();

        outbox.append(new OutboxEventDraft(
                null,
                "RECIPE_YIELD_PROVENANCE_CONFIRMED",
                1,
                workspace.kitchenId(),
                "recipe_version",
                recipeVersionId,
                now,
                reviewId,
                null,
                "recipe-yield-provenance-confirmed:" + recipeVersionId,
                Map.of(
                        "recipeVersionId", recipeVersionId,
                        "yieldQuantity", attested.quantity(),
                        "yieldUnit", attested.unit(),
                        "reviewId", reviewId
                ),
                Map.of("component", "legacy-provenance-review")
        ));

        return new ConfirmationResult(
                "RECIPE_YIELD",
                recipeVersionId,
                null,
                null,
                OWNER_CONFIRMED,
                true,
                reviewId
        );
    }

    @Transactional
    public ConfirmationResult confirmOrderRecipePin(
            String orderId,
            int lineNumber,
            String attestedRecipeVersionId,
            String reason,
            String actor
    ) {
        requireText(orderId, "Order");
        requireText(attestedRecipeVersionId, "Attested recipe version");
        requireText(reason, "Review reason");
        requireText(actor, "Reviewer");
        if (lineNumber < 1) {
            throw new DemandValidationException("Order line must be positive.");
        }

        OrderPinRow current = jdbc.sql("""
                        SELECT item.dish_id,
                               item.recipe_version_id,
                               item.recipe_pin_provenance
                        FROM order_items item
                        JOIN customer_orders orders ON orders.id = item.order_id
                        WHERE item.order_id = :orderId
                          AND item.line_number = :lineNumber
                          AND orders.kitchen_id = :kitchen
                          AND orders.location_id = :location
                        FOR UPDATE
                        """)
                .param("orderId", orderId)
                .param("lineNumber", lineNumber)
                .param("kitchen", workspace.kitchenId())
                .param("location", workspace.locationId())
                .query((rs, row) -> new OrderPinRow(
                        rs.getString("dish_id"),
                        rs.getString("recipe_version_id"),
                        rs.getString("recipe_pin_provenance")
                ))
                .optional()
                .orElseThrow(() -> new DemandValidationException(
                        "Order line was not found in this workspace."
                ));

        if (!current.recipeVersionId().equals(attestedRecipeVersionId.trim())) {
            throw new DemandValidationException(
                    "The attestation must match the provisional recipe pin. "
                            + "Cancel and recreate the legacy order when records show "
                            + "a different recipe; accepted history is never rewritten."
            );
        }
        boolean matchingRecipe = jdbc.sql("""
                        SELECT COUNT(*)
                        FROM recipe_versions
                        WHERE id = :recipe
                          AND dish_id = :dish
                          AND kitchen_id = :kitchen
                        """)
                .param("recipe", current.recipeVersionId())
                .param("dish", current.dishId())
                .param("kitchen", workspace.kitchenId())
                .query(Long.class)
                .single() == 1L;
        if (!matchingRecipe) {
            throw new DemandValidationException(
                    "The attested recipe is not a version of the ordered dish."
            );
        }
        if (OWNER_CONFIRMED.equals(current.provenance())) {
            return new ConfirmationResult(
                    "ORDER_RECIPE_PIN",
                    current.recipeVersionId(),
                    orderId,
                    lineNumber,
                    OWNER_CONFIRMED,
                    false,
                    null
            );
        }
        if (!"LEGACY_RECONSTRUCTED".equals(current.provenance())) {
            throw new DemandValidationException(
                    "This order recipe pin does not require legacy review."
            );
        }

        Instant now = Instant.now();
        int changed = jdbc.sql("""
                        UPDATE order_items
                        SET recipe_pin_provenance = 'OWNER_CONFIRMED'
                        WHERE order_id = :orderId
                          AND line_number = :lineNumber
                          AND recipe_pin_provenance = 'LEGACY_RECONSTRUCTED'
                        """)
                .param("orderId", orderId)
                .param("lineNumber", lineNumber)
                .update();
        requireSingleChange(changed);

        String reviewId = UUID.randomUUID().toString();
        jdbc.sql("""
                        INSERT INTO legacy_provenance_reviews
                        (id, review_type, kitchen_id, location_id,
                         order_id, line_number, recipe_version_id,
                         previous_provenance, resulting_provenance,
                         reason, reviewed_by, reviewed_at)
                        VALUES
                        (:id, 'ORDER_RECIPE_PIN', :kitchen, :location,
                         :orderId, :lineNumber, :recipe,
                         :previous, 'OWNER_CONFIRMED',
                         :reason, :actor, :reviewedAt)
                        """)
                .param("id", reviewId)
                .param("kitchen", workspace.kitchenId())
                .param("location", workspace.locationId())
                .param("orderId", orderId)
                .param("lineNumber", lineNumber)
                .param("recipe", current.recipeVersionId())
                .param("previous", current.provenance())
                .param("reason", reason.trim())
                .param("actor", actor.trim())
                .param("reviewedAt", JdbcTimestamp.utc(now))
                .update();

        outbox.append(new OutboxEventDraft(
                null,
                "ORDER_RECIPE_PIN_CONFIRMED",
                1,
                workspace.kitchenId(),
                "order_item",
                orderId + ":" + lineNumber,
                now,
                orderId,
                reviewId,
                "order-recipe-pin-confirmed:" + orderId + ":" + lineNumber,
                Map.of(
                        "orderId", orderId,
                        "lineNumber", lineNumber,
                        "recipeVersionId", current.recipeVersionId(),
                        "reviewId", reviewId
                ),
                Map.of("component", "legacy-provenance-review")
        ));

        return new ConfirmationResult(
                "ORDER_RECIPE_PIN",
                current.recipeVersionId(),
                orderId,
                lineNumber,
                OWNER_CONFIRMED,
                true,
                reviewId
        );
    }

    private static void requireSingleChange(int changed) {
        if (changed != 1) {
            throw new RecommendationConflictException(
                    "Legacy provenance changed concurrently; reload the review queue."
            );
        }
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new DemandValidationException(label + " is required.");
        }
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    public record ReviewQueue(
            List<RecipeYieldReview> recipeYields,
            List<OrderRecipePinReview> orderRecipePins
    ) {
    }

    public record RecipeYieldReview(
            String recipeVersionId,
            String dishId,
            String dishName,
            BigDecimal yieldQuantity,
            String yieldUnit,
            String provenance
    ) {
    }

    public record OrderRecipePinReview(
            String orderId,
            int lineNumber,
            String dishId,
            String dishName,
            String provisionalRecipeVersionId,
            String provenance,
            int orderedQuantity,
            int preparedQuantity,
            Instant orderedAt,
            Instant requiredAt
    ) {
    }

    public record ConfirmationResult(
            String reviewType,
            String recipeVersionId,
            String orderId,
            Integer lineNumber,
            String provenance,
            boolean changed,
            String reviewId
    ) {
    }

    private record YieldRow(
            BigDecimal quantity,
            String unit,
            String provenance
    ) {
    }

    private record OrderPinRow(
            String dishId,
            String recipeVersionId,
            String provenance
    ) {
    }
}
