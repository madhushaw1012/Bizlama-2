package com.bizlama.api.recommendations;

import com.bizlama.api.config.JdbcTimestamp;
import com.bizlama.api.domain.Order;
import com.bizlama.api.domain.StockMovement;
import com.bizlama.api.outbox.OutboxEventDraft;
import com.bizlama.api.outbox.TransactionalOutboxService;
import com.bizlama.api.orders.OrderTransitionService;
import com.bizlama.api.quantity.IncompatibleUnitException;
import com.bizlama.api.quantity.UnitConversionService;
import com.bizlama.api.stock.InventoryAllocationService;
import com.bizlama.api.stock.InventoryAllocationService.AllocationResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Creates the real operational object referenced by an applied recommendation. */
@Service
public class RecommendationActionService {

    private static final MathContext CALCULATION_CONTEXT = MathContext.DECIMAL128;

    private final JdbcClient jdbc;
    private final InventoryAllocationService allocations;
    private final UnitConversionService units;
    private final TransactionalOutboxService outbox;
    private final OrderTransitionService orderTransitions;
    private final ObjectMapper json;

    public RecommendationActionService(
            JdbcClient jdbc,
            InventoryAllocationService allocations,
            UnitConversionService units,
            TransactionalOutboxService outbox,
            OrderTransitionService orderTransitions,
            ObjectMapper json
    ) {
        this.jdbc = jdbc;
        this.allocations = allocations;
        this.units = units;
        this.outbox = outbox;
        this.orderTransitions = orderTransitions;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ActionReference apply(
            GovernedRecommendation recommendation,
            Instant actionAt,
            String actor
    ) {
        return switch (recommendation.type()) {
            case PREPARE -> completeProduction(recommendation, actionAt, actor);
            case PURCHASE -> recordPlan(
                    recommendation,
                    "PURCHASE_REQUEST",
                    actionAt,
                    actor
            );
            case UTILISE_EXPIRING_STOCK -> recordPlan(
                    recommendation,
                    "UTILISATION_PLAN",
                    actionAt,
                    actor
            );
            case REDUCE_OR_AVOID_PURCHASE -> recordPlan(
                    recommendation,
                    "PURCHASE_AVOIDANCE",
                    actionAt,
                    actor
            );
        };
    }

    private ActionReference completeProduction(
            GovernedRecommendation recommendation,
            Instant actionAt,
            String actor
    ) {
        int requestedCount = exactCount(
                recommendation.proposedQuantity(),
                recommendation.unit()
        );
        Instant horizonEnd = jdbc.sql("""
                        SELECT horizon_end
                        FROM demand_calculation_snapshots
                        WHERE id = :calculation
                          AND kitchen_id = :kitchen
                          AND location_id = :location
                        """)
                .param("calculation", recommendation.calculationId())
                .param("kitchen", recommendation.kitchenId())
                .param("location", recommendation.locationId())
                .query(OffsetDateTime.class)
                .optional()
                .map(OffsetDateTime::toInstant)
                .orElseThrow(() -> new RecommendationConflictException(
                        "Recommendation calculation scope is unavailable."
                ));

        List<LockedOrderLine> availableLines = jdbc.sql("""
                        SELECT orders.id AS order_id,
                               item.line_number,
                               item.quantity,
                               item.prepared_quantity
                        FROM customer_orders orders
                        JOIN order_items item ON item.order_id = orders.id
                        WHERE orders.kitchen_id = :kitchen
                          AND orders.location_id = :location
                          AND orders.status IN ('QUEUED', 'PREPARING')
                          AND orders.created_at <= :actionAt
                          AND orders.required_at < :horizonEnd
                          AND item.dish_id = :dish
                          AND item.recipe_version_id = :recipe
                          AND item.prepared_quantity < item.quantity
                        ORDER BY orders.required_at, orders.id, item.line_number
                        FOR UPDATE
                        """)
                .param("kitchen", recommendation.kitchenId())
                .param("location", recommendation.locationId())
                .param("actionAt", JdbcTimestamp.utc(actionAt))
                .param("horizonEnd", JdbcTimestamp.utc(horizonEnd))
                .param("dish", recommendation.dishId())
                .param("recipe", recommendation.recipeVersionId())
                .query((rs, ignored) -> new LockedOrderLine(
                        rs.getString("order_id"),
                        rs.getInt("line_number"),
                        rs.getInt("quantity"),
                        rs.getInt("prepared_quantity")
                ))
                .list();

        List<PreparedOrderLine> preparedLines = planOrderLines(
                availableLines,
                requestedCount
        );
        RecipeYield recipe = recipeYield(recommendation);
        List<RecipeIngredient> ingredients = recipeIngredients(recommendation);
        String actionId = "PA-" + UUID.randomUUID();
        List<AllocationResult> ingredientAllocations = new ArrayList<>();

        for (RecipeIngredient ingredient : ingredients) {
            BigDecimal required = ingredient.quantity()
                    .multiply(BigDecimal.valueOf(requestedCount), CALCULATION_CONTEXT)
                    .divide(recipe.yieldEach(), CALCULATION_CONTEXT);
            ingredientAllocations.add(allocations.allocate(
                    recommendation.kitchenId(),
                    recommendation.locationId(),
                    ingredient.ingredientId(),
                    required,
                    ingredient.unit(),
                    actionAt,
                    StockMovement.MovementType.PRODUCTION_CONSUMPTION,
                    "recommendation-production-action",
                    actionId
            ));
        }

        ProductionDetails details = new ProductionDetails(
                recommendation.id(),
                recommendation.calculationId(),
                recommendation.dishId(),
                recommendation.recipeVersionId(),
                requestedCount,
                "each",
                List.copyOf(preparedLines),
                List.copyOf(ingredientAllocations)
        );
        insertAction(
                actionId,
                recommendation,
                "PRODUCTION_RUN",
                "COMPLETED",
                BigDecimal.valueOf(requestedCount),
                "each",
                details,
                actionAt,
                actor
        );
        persistPreparedLines(recommendation, actionId, preparedLines);
        updateOrderStatuses(recommendation, actionId, preparedLines, actionAt, actor);
        appendActionEvent(
                recommendation,
                actionId,
                "PRODUCTION_RUN_COMPLETED",
                details,
                actionAt
        );
        return new ActionReference("PRODUCTION_RUN", actionId);
    }

    private ActionReference recordPlan(
            GovernedRecommendation recommendation,
            String actionType,
            Instant actionAt,
            String actor
    ) {
        String actionId = "PA-" + UUID.randomUUID();
        PlanDetails details = new PlanDetails(
                recommendation.id(),
                recommendation.calculationId(),
                recommendation.reasonCode(),
                recommendation.ingredientId(),
                recommendation.proposedQuantity(),
                recommendation.unit()
        );
        insertAction(
                actionId,
                recommendation,
                actionType,
                "CREATED",
                recommendation.proposedQuantity(),
                recommendation.unit(),
                details,
                actionAt,
                actor
        );
        appendActionEvent(
                recommendation,
                actionId,
                "RECOMMENDATION_ACTION_CREATED",
                details,
                actionAt
        );
        return new ActionReference(actionType, actionId);
    }

    private int exactCount(BigDecimal quantity, String unit) {
        try {
            BigDecimal each = units.convert(quantity, unit, "each").quantity();
            int value = each.intValueExact();
            if (value <= 0) {
                throw new ArithmeticException("not positive");
            }
            return value;
        } catch (ArithmeticException | IncompatibleUnitException error) {
            throw new DemandValidationException(
                    "Preparation actions require a positive whole-number quantity in each.",
                    error
            );
        }
    }

    private List<PreparedOrderLine> planOrderLines(
            List<LockedOrderLine> available,
            int requestedCount
    ) {
        int remaining = requestedCount;
        List<PreparedOrderLine> result = new ArrayList<>();
        for (LockedOrderLine line : available) {
            if (remaining == 0) {
                break;
            }
            int open = line.quantity() - line.preparedQuantity();
            int applied = Math.min(open, remaining);
            result.add(new PreparedOrderLine(
                    line.orderId(),
                    line.lineNumber(),
                    line.preparedQuantity(),
                    line.preparedQuantity() + applied,
                    applied
            ));
            remaining -= applied;
        }
        if (remaining != 0) {
            throw new RecommendationConflictException(
                    "Pinned order demand changed; reload before applying this recommendation."
            );
        }
        return result;
    }

    private RecipeYield recipeYield(GovernedRecommendation recommendation) {
        return jdbc.sql("""
                        SELECT recipe.yield_quantity, recipe.yield_unit
                        FROM recipe_versions recipe
                        JOIN dishes dish
                          ON dish.id = recipe.dish_id
                         AND dish.kitchen_id = recipe.kitchen_id
                        WHERE recipe.id = :recipe
                          AND recipe.dish_id = :dish
                          AND recipe.kitchen_id = :kitchen
                          AND dish.kitchen_id = :kitchen
                        """)
                .param("recipe", recommendation.recipeVersionId())
                .param("dish", recommendation.dishId())
                .param("kitchen", recommendation.kitchenId())
                .query((rs, ignored) -> {
                    try {
                        BigDecimal each = units.convert(
                                rs.getBigDecimal("yield_quantity"),
                                rs.getString("yield_unit"),
                                "each"
                        ).quantity();
                        if (each.signum() <= 0) {
                            throw new DemandValidationException(
                                    "Pinned recipe yield must be positive."
                            );
                        }
                        return new RecipeYield(each);
                    } catch (IncompatibleUnitException error) {
                        throw new DemandValidationException(
                                "Pinned recipe yield must use a count unit.",
                                error
                        );
                    }
                })
                .optional()
                .orElseThrow(() -> new RecommendationConflictException(
                        "Pinned recipe version is unavailable in this kitchen."
                ));
    }

    private List<RecipeIngredient> recipeIngredients(
            GovernedRecommendation recommendation
    ) {
        List<RecipeIngredient> result = jdbc.sql("""
                        SELECT recipe_item.ingredient_id,
                               recipe_item.quantity,
                               recipe_item.unit
                        FROM recipe_ingredients recipe_item
                        JOIN ingredients ingredient
                          ON ingredient.id = recipe_item.ingredient_id
                         AND ingredient.kitchen_id = recipe_item.kitchen_id
                        WHERE recipe_item.recipe_version_id = :recipe
                          AND recipe_item.kitchen_id = :kitchen
                          AND ingredient.kitchen_id = :kitchen
                          AND ingredient.active = TRUE
                        ORDER BY recipe_item.ingredient_id
                        """)
                .param("recipe", recommendation.recipeVersionId())
                .param("kitchen", recommendation.kitchenId())
                .query((rs, ignored) -> new RecipeIngredient(
                        rs.getString("ingredient_id"),
                        rs.getBigDecimal("quantity"),
                        rs.getString("unit")
                ))
                .list();
        if (result.isEmpty()) {
            throw new RecommendationConflictException(
                    "Pinned recipe has no active ingredient definition."
            );
        }
        return result;
    }

    private void insertAction(
            String actionId,
            GovernedRecommendation recommendation,
            String actionType,
            String status,
            BigDecimal quantity,
            String unit,
            Object details,
            Instant actionAt,
            String actor
    ) {
        jdbc.sql("""
                        INSERT INTO recommendation_actions
                        (id, recommendation_id, kitchen_id, location_id,
                         action_type, action_status, ingredient_id, dish_id,
                         recipe_version_id, quantity, unit, details_json,
                         created_at, created_by)
                        VALUES
                        (:id, :recommendation, :kitchen, :location,
                         :type, :status, :ingredient, :dish,
                         :recipe, :quantity, :unit, :details,
                         :createdAt, :createdBy)
                        """)
                .param("id", actionId)
                .param("recommendation", recommendation.id())
                .param("kitchen", recommendation.kitchenId())
                .param("location", recommendation.locationId())
                .param("type", actionType)
                .param("status", status)
                .param("ingredient", recommendation.ingredientId())
                .param("dish", recommendation.dishId())
                .param("recipe", recommendation.recipeVersionId())
                .param("quantity", quantity)
                .param("unit", unit)
                .param("details", toJson(details))
                .param("createdAt", JdbcTimestamp.utc(actionAt))
                .param("createdBy", actor)
                .update();
    }

    private void persistPreparedLines(
            GovernedRecommendation recommendation,
            String actionId,
            List<PreparedOrderLine> preparedLines
    ) {
        for (PreparedOrderLine line : preparedLines) {
            int changed = jdbc.sql("""
                            UPDATE order_items
                            SET prepared_quantity = :after
                            WHERE order_id = :orderId
                              AND line_number = :lineNumber
                              AND prepared_quantity = :before
                              AND quantity >= :after
                              AND EXISTS (
                                SELECT 1
                                FROM customer_orders scoped_order
                                WHERE scoped_order.id = order_items.order_id
                                  AND scoped_order.kitchen_id = :kitchen
                                  AND scoped_order.location_id = :location)
                            """)
                    .param("after", line.afterPreparedQuantity())
                    .param("orderId", line.orderId())
                    .param("lineNumber", line.lineNumber())
                    .param("before", line.beforePreparedQuantity())
                    .param("kitchen", recommendation.kitchenId())
                    .param("location", recommendation.locationId())
                    .update();
            if (changed != 1) {
                throw new RecommendationConflictException(
                        "Pinned order demand changed during production; retry from fresh data."
                );
            }
            int linked = jdbc.sql("""
                            INSERT INTO recommendation_action_order_lines
                            (action_id, order_id, line_number, prepared_quantity)
                            SELECT action.id, orders.id, :lineNumber, :quantity
                            FROM recommendation_actions action
                            JOIN customer_orders orders ON orders.id = :orderId
                            WHERE action.id = :action
                              AND action.kitchen_id = :kitchen
                              AND action.location_id = :location
                              AND orders.kitchen_id = :kitchen
                              AND orders.location_id = :location
                            """)
                    .param("action", actionId)
                    .param("orderId", line.orderId())
                    .param("lineNumber", line.lineNumber())
                    .param("quantity", line.appliedQuantity())
                    .param("kitchen", recommendation.kitchenId())
                    .param("location", recommendation.locationId())
                    .update();
            if (linked != 1) {
                throw new RecommendationConflictException(
                        "Production action or order left this workspace."
                );
            }
        }
    }

    private void updateOrderStatuses(
            GovernedRecommendation recommendation,
            String actionId,
            List<PreparedOrderLine> preparedLines,
            Instant actionAt,
            String actor
    ) {
        Set<String> orderIds = new LinkedHashSet<>();
        preparedLines.forEach(line -> orderIds.add(line.orderId()));
        for (String orderId : orderIds) {
            OrderState state = jdbc.sql("""
                            SELECT orders.status,
                                   SUM(CASE
                                     WHEN item.prepared_quantity < item.quantity
                                     THEN 1 ELSE 0 END) AS open_lines
                            FROM customer_orders orders
                            JOIN order_items item ON item.order_id = orders.id
                            WHERE orders.id = :orderId
                              AND orders.kitchen_id = :kitchen
                              AND orders.location_id = :location
                            GROUP BY orders.status
                            """)
                    .param("orderId", orderId)
                    .param("kitchen", recommendation.kitchenId())
                    .param("location", recommendation.locationId())
                    .query((rs, ignored) -> new OrderState(
                            rs.getString("status"),
                            rs.getInt("open_lines")
                    ))
                    .single();

            Order.Status current = Order.Status.valueOf(state.status());
            if (current == Order.Status.QUEUED) {
                orderTransitions.transition(
                        recommendation.kitchenId(),
                        recommendation.locationId(),
                        orderId,
                        Order.Status.PREPARING,
                        actionAt,
                        actor,
                        "Preparation started by recommendation production action "
                                + actionId + ".",
                        actionId
                );
                current = Order.Status.PREPARING;
            }
            if (state.openLines() == 0 && current == Order.Status.PREPARING) {
                orderTransitions.transition(
                        recommendation.kitchenId(),
                        recommendation.locationId(),
                        orderId,
                        Order.Status.DONE,
                        actionAt,
                        actor,
                        "All lines prepared by recommendation production action "
                                + actionId + ".",
                        actionId
                );
            }
        }
    }

    private void appendActionEvent(
            GovernedRecommendation recommendation,
            String actionId,
            String eventType,
            Object details,
            Instant actionAt
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("actionId", actionId);
        payload.put("recommendationId", recommendation.id());
        payload.put("recommendationType", recommendation.type().name());
        payload.put("locationId", recommendation.locationId());
        payload.put("quantity", recommendation.proposedQuantity());
        payload.put("unit", recommendation.unit());
        payload.put("details", details);
        outbox.append(new OutboxEventDraft(
                null,
                eventType,
                1,
                recommendation.kitchenId(),
                "recommendation_action",
                actionId,
                actionAt,
                recommendation.id(),
                null,
                "recommendation-action:" + actionId,
                payload,
                Map.of("component", "recommendation-action-service")
        ));
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException(
                    "Recommendation action evidence could not be serialized.",
                    error
            );
        }
    }

    public record ActionReference(String type, String id) {
    }

    private record LockedOrderLine(
            String orderId,
            int lineNumber,
            int quantity,
            int preparedQuantity
    ) {
    }

    public record PreparedOrderLine(
            String orderId,
            int lineNumber,
            int beforePreparedQuantity,
            int afterPreparedQuantity,
            int appliedQuantity
    ) {
    }

    private record RecipeYield(BigDecimal yieldEach) {
    }

    private record RecipeIngredient(
            String ingredientId,
            BigDecimal quantity,
            String unit
    ) {
    }

    private record OrderState(String status, int openLines) {
    }

    public record ProductionDetails(
            String recommendationId,
            String calculationId,
            String dishId,
            String recipeVersionId,
            int quantity,
            String unit,
            List<PreparedOrderLine> orderLines,
            List<AllocationResult> ingredientAllocations
    ) {
    }

    public record PlanDetails(
            String recommendationId,
            String calculationId,
            String reasonCode,
            String ingredientId,
            BigDecimal quantity,
            String unit
    ) {
    }
}
