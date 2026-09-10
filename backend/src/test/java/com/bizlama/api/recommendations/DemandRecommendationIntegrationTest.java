package com.bizlama.api.recommendations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bizlama.api.recommendations.DemandCalculation.DemandScope;
import com.bizlama.api.recommendations.DemandCalculation.IngredientDemand;
import com.bizlama.api.recommendations.GovernedRecommendation.GenerationResult;
import com.bizlama.api.recommendations.GovernedRecommendation.Status;
import com.bizlama.api.recommendations.GovernedRecommendation.Type;
import com.bizlama.api.domain.Ingredient;
import com.bizlama.api.quantity.IncompatibleUnitException;
import com.bizlama.api.store.OperationalRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("memory")
@Transactional
class DemandRecommendationIntegrationTest {

    private static final String KITCHEN = "kitchen-default";
    private static final String LOCATION = "location-main";

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DemandCalculationService demand;

    @Autowired
    private RecommendationService recommendations;

    @Autowired
    private OperationalRepository repository;

    private String ingredientId;
    private String dishId;
    private String exactRecipeVersionId;
    private String currentRecipeVersionId;
    private String orderId;
    private String survivingLotId;
    private DemandScope scope;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString();
        ingredientId = "demand-ingredient-" + suffix;
        dishId = "demand-dish-" + suffix;
        exactRecipeVersionId = "demand-recipe-v1-" + suffix;
        currentRecipeVersionId = "demand-recipe-v2-" + suffix;
        orderId = "demand-order-" + suffix;

        Instant asOf = Instant.now().plusSeconds(2);
        Instant orderedAt = asOf.minusSeconds(60);
        Instant requiredAt = asOf.plusSeconds(24 * 60 * 60);
        scope = new DemandScope(
                KITCHEN,
                LOCATION,
                asOf.minusSeconds(60 * 60),
                asOf.plusSeconds(3 * 24 * 60 * 60),
                asOf
        );
        ZoneId kitchenZone = ZoneId.of("Asia/Kolkata");
        LocalDate asOfDate = asOf.atZone(kitchenZone).toLocalDate();
        LocalDate horizonEndDate = scope.horizonEnd()
                .minusNanos(1)
                .atZone(kitchenZone)
                .toLocalDate();

        jdbc.sql("""
                        INSERT INTO ingredients
                        (id, name, base_unit, active, created_at, kitchen_id)
                        VALUES
                        (:id, 'Demand test ingredient', 'g', TRUE, :createdAt, :kitchen)
                        """)
                .param("id", ingredientId)
                .param("createdAt", orderedAt.minusSeconds(60))
                .param("kitchen", KITCHEN)
                .update();
        jdbc.sql("""
                        INSERT INTO dishes
                        (id, name, price, active, created_at, kitchen_id)
                        VALUES
                        (:id, 'Demand test dish', 10.00, TRUE, :createdAt, :kitchen)
                        """)
                .param("id", dishId)
                .param("createdAt", orderedAt.minusSeconds(60))
                .param("kitchen", KITCHEN)
                .update();
        jdbc.sql("""
                        INSERT INTO recipe_versions
                        (id, dish_id, version_number, change_reason, active,
                         created_at, yield_quantity, yield_unit, kitchen_id)
                        VALUES
                        (:v1, :dish, 1, 'Original exact recipe', FALSE,
                         :createdAt, 4.000, 'each', :kitchen),
                        (:v2, :dish, 2, 'New current recipe', FALSE,
                         :createdAt, 2.000, 'each', :kitchen)
                        """)
                .param("v1", exactRecipeVersionId)
                .param("v2", currentRecipeVersionId)
                .param("dish", dishId)
                .param("createdAt", orderedAt.minusSeconds(30))
                .param("kitchen", KITCHEN)
                .update();
        jdbc.sql("""
                        UPDATE recipe_versions
                        SET effective_at = :effectiveAt,
                            active = TRUE,
                            active_dish_guard = :dish
                        WHERE id = :version
                        """)
                .param("effectiveAt", orderedAt.minusSeconds(30))
                .param("dish", dishId)
                .param("version", currentRecipeVersionId)
                .update();
        jdbc.sql("""
                        UPDATE dishes
                        SET active_recipe_version_id = :version
                        WHERE id = :dish
                        """)
                .param("version", currentRecipeVersionId)
                .param("dish", dishId)
                .update();
        jdbc.sql("""
                        INSERT INTO recipe_ingredients
                        (recipe_version_id, ingredient_id, quantity, unit, kitchen_id)
                        VALUES
                        (:v1, :ingredient, 600.000, 'g', :kitchen),
                        (:v2, :ingredient, 400.000, 'g', :kitchen)
                        """)
                .param("v1", exactRecipeVersionId)
                .param("v2", currentRecipeVersionId)
                .param("ingredient", ingredientId)
                .param("kitchen", KITCHEN)
                .update();
        jdbc.sql("""
                        INSERT INTO customer_orders
                        (id, total, status, created_at, kitchen_id, location_id,
                         required_at)
                        VALUES
                        (:id, 40.00, 'QUEUED', :orderedAt, :kitchen, :location,
                         :requiredAt)
                        """)
                .param("id", orderId)
                .param("orderedAt", orderedAt)
                .param("kitchen", KITCHEN)
                .param("location", LOCATION)
                .param("requiredAt", requiredAt)
                .update();
        jdbc.sql("""
                        INSERT INTO order_items
                        (order_id, line_number, dish_id, quantity, unit_price,
                         recipe_version_id, prepared_quantity)
                        VALUES
                        (:orderId, 1, :dish, 4, 10.00, :recipeVersion, 1)
                        """)
                .param("orderId", orderId)
                .param("dish", dishId)
                .param("recipeVersion", exactRecipeVersionId)
                .update();

        insertLot(
                "demand-early-lot-" + suffix,
                new BigDecimal("500.000"),
                asOfDate,
                asOfDate,
                orderedAt.minusSeconds(30)
        );
        survivingLotId = "demand-surviving-lot-" + suffix;
        insertLot(
                survivingLotId,
                new BigDecimal("300.000"),
                asOfDate,
                horizonEndDate.plusDays(1),
                orderedAt.minusSeconds(20)
        );
        demand.configureSafetyStock(
                KITCHEN,
                LOCATION,
                ingredientId,
                new BigDecimal("50"),
                "g",
                null,
                "test-owner"
        );
    }

    @Test
    void exactHistoricalRecipeYieldAndLotExpiryDriveTimePhasedShortage() {
        IngredientDemand result = demand.calculate(scope).ingredients().stream()
                .filter(value -> value.ingredientId().equals(ingredientId))
                .findFirst()
                .orElseThrow();

        assertThat(result.grossDemand()).isEqualByComparingTo("450");
        assertThat(result.onHandUsableSupply()).isEqualByComparingTo("800");
        assertThat(result.horizonEndSurvivingSupply()).isEqualByComparingTo("0");
        assertThat(result.usableSupply()).isEqualByComparingTo("300");
        assertThat(result.safetyStock()).isEqualByComparingTo("50");
        assertThat(result.shortage()).isEqualByComparingTo("200");
        assertThat(result.timePhasedShortfall()).isEqualByComparingTo("150");
        assertThat(result.expiryRiskSurplus()).isEqualByComparingTo("500");
        assertThat(result.contributingRecipeVersionIds())
                .containsExactly(exactRecipeVersionId);
        assertThat(result.orderContributions()).singleElement().satisfies(evidence -> {
            assertThat(evidence.recipeVersionId()).isEqualTo(exactRecipeVersionId);
            assertThat(evidence.orderedQuantity()).isEqualTo(4);
            assertThat(evidence.preparedQuantity()).isEqualTo(1);
            assertThat(evidence.remainingQuantity()).isEqualTo(3);
            assertThat(evidence.recipeYieldQuantity()).isEqualByComparingTo("4");
            assertThat(evidence.canonicalDemand()).isEqualByComparingTo("450");
        });
        assertThat(result.contributingLots()).hasSize(2);
        assertThat(result.contributingLots())
                .filteredOn(lot -> lot.expiryRiskQuantity().signum() > 0)
                .singleElement()
                .satisfies(lot -> {
                    assertThat(lot.allocatedDemand()).isEqualByComparingTo("0");
                    assertThat(lot.expiryRiskQuantity()).isEqualByComparingTo("500");
                });
    }

    @Test
    void queuedPrioritiesAggregateDishAcrossOrdersAndDropAdvancedOrders() {
        String overlappingOrder = "demand-overlap-" + UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO customer_orders
                        (id, total, status, created_at, kitchen_id, location_id,
                         required_at)
                        VALUES
                        (:id, 20.00, 'QUEUED', :createdAt, :kitchen, :location,
                         :requiredAt)
                        """)
                .param("id", overlappingOrder)
                .param("createdAt", scope.asOf().minusSeconds(30))
                .param("kitchen", KITCHEN)
                .param("location", LOCATION)
                .param("requiredAt", scope.asOf().plusSeconds(60 * 60))
                .update();
        jdbc.sql("""
                        INSERT INTO order_items
                        (order_id, line_number, dish_id, quantity, unit_price,
                         recipe_version_id, prepared_quantity)
                        VALUES (:order, 1, :dish, 2, 10.00, :recipe, 0)
                        """)
                .param("order", overlappingOrder)
                .param("dish", dishId)
                .param("recipe", exactRecipeVersionId)
                .update();

        var before = demand.calculate(scope).preparations().stream()
                .filter(value -> dishId.equals(value.dishId()))
                .toList();
        assertThat(before).singleElement().satisfies(priority -> {
            assertThat(priority.dishName()).isEqualTo("Demand test dish");
            assertThat(priority.quantity()).isEqualByComparingTo("5");
            assertThat(priority.contributingOrderIds())
                    .containsExactly(overlappingOrder, orderId);
        });
        assertThat(demand.calculate(scope).includedOrderStatuses())
                .containsExactly("QUEUED");

        repository.updateOrderStatus(
                orderId,
                com.bizlama.api.domain.Order.Status.PREPARING,
                "priority-test"
        );

        var after = demand.calculate(scope).preparations().stream()
                .filter(value -> dishId.equals(value.dishId()))
                .toList();
        assertThat(after).singleElement().satisfies(priority -> {
            assertThat(priority.quantity()).isEqualByComparingTo("2");
            assertThat(priority.contributingOrderIds())
                    .containsExactly(overlappingOrder);
        });
    }

    @Test
    void referencedIngredientCannotChangeQuantityDimension() {
        assertThatThrownBy(() -> repository.saveIngredient(new Ingredient(
                ingredientId,
                "Attempted volume reinterpretation",
                "ml",
                true
        )))
                .isInstanceOf(IncompatibleUnitException.class)
                .hasMessageContaining("cannot change");

        Ingredient sameDimension = repository.saveIngredient(new Ingredient(
                ingredientId,
                "Safe canonical rename",
                "kg",
                true
        ));
        assertThat(sameDimension.baseUnit()).isEqualTo("g");
        assertThat(repository.ingredient(ingredientId))
                .get()
                .extracting(Ingredient::baseUnit)
                .isEqualTo("g");
    }

    @Test
    void generationPersistsSnapshotsAndSupersedesMatchingPendingAdvice() {
        Instant expiresAt = Instant.now().plusSeconds(24 * 60 * 60);
        GenerationResult first = recommendations.generate(
                scope,
                expiresAt,
                "test-owner"
        );
        GovernedRecommendation firstPurchase = recommendation(
                first,
                Type.PURCHASE
        );

        GenerationResult second = recommendations.generate(
                scope,
                expiresAt,
                "test-owner"
        );
        GovernedRecommendation secondPurchase = recommendation(
                second,
                Type.PURCHASE
        );

        assertThat(recommendations.recommendation(firstPurchase.id()).status())
                .isEqualTo(Status.SUPERSEDED);
        assertThat(recommendations.recommendation(firstPurchase.id())
                .supersededByRecommendationId()).isEqualTo(secondPurchase.id());
        assertThat(secondPurchase.status()).isEqualTo(Status.PENDING);
        assertThat(secondPurchase.proposedQuantity()).isEqualByComparingTo("200");
        assertThat(recommendations.calculation(secondPurchase.id()))
                .usingRecursiveComparison()
                .isEqualTo(second.calculation());

        jdbc.sql("""
                        UPDATE stock_lots
                        SET quantity_remaining = 1000.000,
                            source_quantity = 1000.000
                        WHERE id = :id
                        """)
                .param("id", survivingLotId)
                .update();
        GenerationResult resolved = recommendations.generate(
                scope,
                expiresAt,
                "test-owner"
        );
        assertThat(resolved.recommendations())
                .noneMatch(value -> value.type() == Type.PURCHASE
                        && ingredientId.equals(value.ingredientId()));
        GovernedRecommendation noLongerJustified =
                recommendations.recommendation(secondPurchase.id());
        assertThat(noLongerJustified.status()).isEqualTo(Status.SUPERSEDED);
        assertThat(noLongerJustified.supersededByRecommendationId()).isNull();

        assertThat(jdbc.sql("""
                        SELECT COUNT(*)
                        FROM recommendation_decisions
                        WHERE recommendation_id = :id
                        """)
                .param("id", firstPurchase.id())
                .query(Long.class)
                .single()).isEqualTo(2L);
    }


    @Test
    void inventoryFlagRequiresDurableScopedPostFlagCorrectionMovement() {
        GenerationResult generated = recommendations.generate(
                scope,
                Instant.now().plusSeconds(24 * 60 * 60),
                "test-owner"
        );
        GovernedRecommendation purchase = recommendation(generated, Type.PURCHASE);
        GovernedRecommendation flagged = recommendations.flagInventory(
                purchase.id(),
                purchase.version(),
                "test-operator",
                "Physical count is disputed."
        );
        Instant outcomeAt = Instant.now();

        assertThatThrownBy(() -> recommendations.recordOutcome(
                flagged.id(),
                flagged.version(),
                GovernedRecommendation.OutcomeType.CORRECTED_INVENTORY,
                new BigDecimal("200"),
                "g",
                "STOCK_COUNT",
                "count-1",
                "A label is not durable evidence.",
                outcomeAt,
                "test-operator"
        )).isInstanceOf(DemandValidationException.class)
                .hasMessageContaining("stock movement");

        String purchaseMovement = "demand-purchase-movement-" + UUID.randomUUID();
        insertMovement(
                purchaseMovement,
                LOCATION,
                "PURCHASE",
                flagged.decidedAt()
        );
        assertThatThrownBy(() -> recommendations.recordOutcome(
                flagged.id(),
                flagged.version(),
                GovernedRecommendation.OutcomeType.CORRECTED_INVENTORY,
                new BigDecimal("200"),
                "g",
                "STOCK_MOVEMENT",
                purchaseMovement,
                "A purchase does not prove a count correction.",
                outcomeAt,
                "test-operator"
        )).isInstanceOf(DemandValidationException.class)
                .hasMessageContaining("correction or manual-adjustment");

        String adjacentLocation = "demand-adjacent-" + UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO kitchen_locations
                        (id, kitchen_id, name, location_type, active)
                        VALUES (:id, :kitchen, 'Adjacent test location', 'KITCHEN', TRUE)
                        """)
                .param("id", adjacentLocation)
                .param("kitchen", KITCHEN)
                .update();
        String adjacentMovement = "demand-adjacent-movement-" + UUID.randomUUID();
        insertMovement(
                adjacentMovement,
                adjacentLocation,
                "CORRECTION",
                flagged.decidedAt()
        );
        assertThatThrownBy(() -> recommendations.recordOutcome(
                flagged.id(),
                flagged.version(),
                GovernedRecommendation.OutcomeType.CORRECTED_INVENTORY,
                new BigDecimal("200"),
                "g",
                "STOCK_MOVEMENT",
                adjacentMovement,
                "Another location's correction is not evidence here.",
                outcomeAt,
                "test-operator"
        )).isInstanceOf(DemandValidationException.class)
                .hasMessageContaining("same workspace");

        String oldMovement = "demand-old-correction-" + UUID.randomUUID();
        insertMovement(
                oldMovement,
                LOCATION,
                "CORRECTION",
                flagged.decidedAt().minusSeconds(1)
        );
        assertThatThrownBy(() -> recommendations.recordOutcome(
                flagged.id(),
                flagged.version(),
                GovernedRecommendation.OutcomeType.CORRECTED_INVENTORY,
                new BigDecimal("200"),
                "g",
                "STOCK_MOVEMENT",
                oldMovement,
                "An old correction cannot resolve the new flag.",
                outcomeAt,
                "test-operator"
        )).isInstanceOf(DemandValidationException.class)
                .hasMessageContaining("post-flag");

        String correctionMovement = "demand-correction-" + UUID.randomUUID();
        insertMovement(
                correctionMovement,
                LOCATION,
                "CORRECTION",
                flagged.decidedAt()
        );

        GovernedRecommendation.Outcome outcome = recommendations.recordOutcome(
                flagged.id(),
                flagged.version(),
                GovernedRecommendation.OutcomeType.CORRECTED_INVENTORY,
                new BigDecimal("200"),
                "g",
                "STOCK_MOVEMENT",
                correctionMovement,
                "Physical count corrected.",
                outcomeAt,
                "test-operator"
        );
        assertThat(outcome.type())
                .isEqualTo(GovernedRecommendation.OutcomeType.CORRECTED_INVENTORY);
        assertThat(outcome.sourceReferenceType()).isEqualTo("STOCK_MOVEMENT");
        assertThat(outcome.sourceReferenceId()).isEqualTo(correctionMovement);
        assertThat(recommendations.recommendation(flagged.id()).version()).isEqualTo(3);
    }
    @Test
    void applyingPreparationCreatesPinnedProductionActionAndClosesDemand() {
        jdbc.sql("""
                        UPDATE order_items
                        SET prepared_quantity = 0
                        WHERE recipe_version_id = :recipe
                        """)
                .param("recipe", exactRecipeVersionId)
                .update();
        jdbc.sql("""
                        UPDATE stock_lots
                        SET quantity_remaining = 1000.000,
                            source_quantity = 1000.000
                        WHERE id = :id
                        """)
                .param("id", survivingLotId)
                .update();

        Instant now = Instant.now();
        LocalDate actionDate = now.atZone(ZoneId.of("Asia/Kolkata")).toLocalDate();
        String futureLotId = "future-production-lot-" + UUID.randomUUID();
        insertLot(
                futureLotId,
                new BigDecimal("100.000"),
                actionDate.plusDays(1),
                actionDate.plusDays(2),
                now
        );

        GenerationResult generated = recommendations.generate(
                scope,
                Instant.now().plusSeconds(24 * 60 * 60),
                "test-owner"
        );
        GovernedRecommendation preparation = generated.recommendations().stream()
                .filter(value -> value.type() == Type.PREPARE)
                .filter(value -> exactRecipeVersionId.equals(value.recipeVersionId()))
                .findFirst()
                .orElseThrow();
        assertThat(preparation.proposedQuantity()).isEqualByComparingTo("4");

        GovernedRecommendation approved = recommendations.approve(
                preparation.id(),
                preparation.version(),
                "test-owner",
                "Prepare the outstanding pinned orders."
        );
        GovernedRecommendation applied = recommendations.markApplied(
                approved.id(),
                approved.version(),
                "test-owner",
                "Start the governed production run."
        );

        assertThat(applied.status()).isEqualTo(Status.APPLIED);
        assertThat(applied.appliedActionType()).isEqualTo("PRODUCTION_RUN");
        assertThat(applied.appliedActionId()).startsWith("PA-");
        assertThat(jdbc.sql("""
                        SELECT prepared_quantity
                        FROM order_items
                        WHERE recipe_version_id = :recipe
                        """)
                .param("recipe", exactRecipeVersionId)
                .query(Integer.class)
                .single()).isEqualTo(4);
        assertThat(jdbc.sql("""
                        SELECT status
                        FROM customer_orders orders
                        JOIN order_items item ON item.order_id = orders.id
                        WHERE item.recipe_version_id = :recipe
                        """)
                .param("recipe", exactRecipeVersionId)
                .query(String.class)
                .single()).isEqualTo("DONE");
        assertThat(jdbc.sql("""
                        SELECT recipe_version_id
                        FROM recommendation_actions
                        WHERE id = :action
                          AND recommendation_id = :recommendation
                          AND action_type = 'PRODUCTION_RUN'
                          AND action_status = 'COMPLETED'
                        """)
                .param("action", applied.appliedActionId())
                .param("recommendation", applied.id())
                .query(String.class)
                .single()).isEqualTo(exactRecipeVersionId);
        assertThat(jdbc.sql("""
                        SELECT prepared_quantity
                        FROM recommendation_action_order_lines
                        WHERE action_id = :action
                        """)
                .param("action", applied.appliedActionId())
                .query(Integer.class)
                .single()).isEqualTo(4);
        assertThat(jdbc.sql("""
                        SELECT COALESCE(SUM(quantity_change), 0)
                        FROM stock_movements
                        WHERE reference_type = 'recommendation-production-action'
                          AND reference_id = :action
                        """)
                .param("action", applied.appliedActionId())
                .query(BigDecimal.class)
                .single()).isEqualByComparingTo("-600");
        assertThat(jdbc.sql("""
                        SELECT quantity_remaining
                        FROM stock_lots
                        WHERE id = :lot
                        """)
                .param("lot", futureLotId)
                .query(BigDecimal.class)
                .single()).isEqualByComparingTo("100");
        assertThat(jdbc.sql("""
                        SELECT history.status
                        FROM order_status_history history
                        JOIN order_items item ON item.order_id = history.order_id
                        WHERE item.recipe_version_id = :recipe
                          AND history.changed_by = 'test-owner'
                          AND history.note LIKE :actionNote
                        """)
                .param("recipe", exactRecipeVersionId)
                .param("actionNote", "%" + applied.appliedActionId() + "%")
                .query(String.class)
                .list()).containsExactlyInAnyOrder("PREPARING", "DONE");
        assertThat(jdbc.sql("""
                        SELECT COUNT(*)
                        FROM analytics_outbox event
                        JOIN order_items item ON item.order_id = event.aggregate_id
                        WHERE item.recipe_version_id = :recipe
                          AND event.aggregate_type = 'order'
                          AND event.event_type = 'ORDER_STATUS_CHANGED'
                          AND event.payload_json LIKE :actionPayload
                        """)
                .param("recipe", exactRecipeVersionId)
                .param("actionPayload", "%" + applied.appliedActionId() + "%")
                .query(Long.class)
                .single()).isEqualTo(2L);
        String doneOrderId = jdbc.sql("""
                        SELECT item.order_id
                        FROM order_items item
                        WHERE item.recipe_version_id = :recipe
                        """)
                .param("recipe", exactRecipeVersionId)
                .query(String.class)
                .single();
        assertThat(repository.order(doneOrderId).orElseThrow().status())
                .isEqualTo(com.bizlama.api.domain.Order.Status.DONE);
        assertThat(jdbc.sql("""
                        SELECT COUNT(*)
                        FROM order_status_history
                        WHERE order_id = :order
                          AND status = 'DONE'
                          AND changed_by = 'test-owner'
                        """)
                .param("order", doneOrderId)
                .query(Long.class)
                .single()).isEqualTo(1L);
        assertThat(jdbc.sql("""
                        SELECT active_recipe_version_id
                        FROM dishes
                        WHERE id = :dish
                        """)
                .param("dish", dishId)
                .query(String.class)
                .single()).isEqualTo(currentRecipeVersionId);
        assertThat(demand.calculate(scope).preparations())
                .noneMatch(value -> exactRecipeVersionId.equals(
                        value.recipeVersionId()
                ));
        assertThatThrownBy(() -> recommendations.recordOutcome(
                applied.id(),
                applied.version(),
                GovernedRecommendation.OutcomeType.EMERGENCY_PURCHASED,
                new BigDecimal("4"),
                "each",
                applied.appliedActionType(),
                applied.appliedActionId(),
                "A purchase outcome cannot be attributed to preparation.",
                Instant.now(),
                "test-operator"
        )).isInstanceOf(DemandValidationException.class);
        assertThatThrownBy(() -> recommendations.recordOutcome(
                applied.id(),
                applied.version(),
                GovernedRecommendation.OutcomeType.PREPARED,
                new BigDecimal("4"),
                "each",
                applied.appliedActionType(),
                "not-the-production-action",
                "Fabricated source evidence must be rejected.",
                Instant.now(),
                "test-operator"
        )).isInstanceOf(DemandValidationException.class);

        GovernedRecommendation.Outcome outcome = recommendations.recordOutcome(
                applied.id(),
                applied.version(),
                GovernedRecommendation.OutcomeType.PREPARED,
                new BigDecimal("4"),
                "each",
                applied.appliedActionType(),
                applied.appliedActionId(),
                "Counted after the production run.",
                Instant.now(),
                "test-operator"
        );
        assertThat(outcome.quantity()).isEqualByComparingTo("4");
        assertThat(outcome.sourceReferenceId()).isEqualTo(applied.appliedActionId());

        assertThatThrownBy(() -> recommendations.markApplied(
                applied.id(),
                applied.version(),
                "test-owner",
                "A duplicate application must not create a second action."
        )).isInstanceOf(RecommendationConflictException.class);
        assertThat(jdbc.sql("""
                        SELECT COUNT(*)
                        FROM recommendation_actions
                        WHERE recommendation_id = :recommendation
                        """)
                .param("recommendation", applied.id())
                .query(Long.class)
                .single()).isEqualTo(1L);
    }


    @Test
    void expiryRiskCreatesMeasuredReversibleUtilisationPlan() {
        GenerationResult generated = recommendations.generate(
                scope,
                Instant.now().plusSeconds(24 * 60 * 60),
                "test-owner"
        );
        GovernedRecommendation utilisation = recommendation(
                generated,
                Type.UTILISE_EXPIRING_STOCK
        );
        assertThat(utilisation.proposedQuantity())
                .isEqualByComparingTo("500");
        assertThat(utilisation.status()).isEqualTo(Status.PENDING);

        GovernedRecommendation approved = recommendations.approve(
                utilisation.id(),
                utilisation.version(),
                "test-owner",
                "Use the expiring lot before its evidence-backed expiry."
        );
        GovernedRecommendation applied = recommendations.markApplied(
                approved.id(),
                approved.version(),
                "test-owner",
                "Create the governed utilisation plan."
        );

        assertThat(applied.status()).isEqualTo(Status.APPLIED);
        assertThat(applied.appliedActionType()).isEqualTo("UTILISATION_PLAN");
        assertThat(applied.appliedActionId()).startsWith("PA-");
        assertThat(jdbc.sql("""
                        SELECT action_status
                        FROM recommendation_actions
                        WHERE id = :action
                          AND recommendation_id = :recommendation
                          AND action_type = 'UTILISATION_PLAN'
                        """)
                .param("action", applied.appliedActionId())
                .param("recommendation", applied.id())
                .query(String.class)
                .single()).isEqualTo("CREATED");

        GovernedRecommendation.Outcome outcome = recommendations.recordOutcome(
                applied.id(),
                applied.version(),
                GovernedRecommendation.OutcomeType.SOLD,
                new BigDecimal("120"),
                "g",
                applied.appliedActionType(),
                applied.appliedActionId(),
                "Measured from the governed utilisation batch.",
                Instant.now(),
                "test-operator"
        );
        assertThat(outcome.quantity()).isEqualByComparingTo("120");
        assertThat(outcome.unit()).isEqualTo("g");
        assertThat(outcome.sourceReferenceId())
                .isEqualTo(applied.appliedActionId());

        GovernedRecommendation afterOutcome =
                recommendations.recommendation(applied.id());
        GovernedRecommendation reversed = recommendations.reverse(
                afterOutcome.id(),
                afterOutcome.version(),
                "test-owner",
                "Stop the remaining plan after service conditions changed."
        );
        assertThat(reversed.status()).isEqualTo(Status.REVERSED);
        assertThat(reversed.appliedActionId())
                .isEqualTo(applied.appliedActionId());
        assertThat(recommendations.outcomes(reversed.id()))
                .extracting(GovernedRecommendation.Outcome::id)
                .containsExactly(outcome.id());
    }

    @Test
    void readingElapsedAdviceTransitionsItToExpiredWithAudit() {
        GenerationResult generated = recommendations.generate(
                scope,
                Instant.now().plusSeconds(24 * 60 * 60),
                "test-owner"
        );
        GovernedRecommendation purchase = recommendation(generated, Type.PURCHASE);
        Instant now = Instant.now();
        jdbc.sql("""
                        UPDATE recommendations
                        SET created_at = :createdAt,
                            expires_at = :expiresAt,
                            updated_at = :updatedAt
                        WHERE id = :id
                        """)
                .param("createdAt", now.minusSeconds(120))
                .param("expiresAt", now.minusSeconds(60))
                .param("updatedAt", now)
                .param("id", purchase.id())
                .update();

        GovernedRecommendation expired = recommendations.recommendation(purchase.id());
        assertThat(expired.status()).isEqualTo(Status.EXPIRED);
        assertThat(expired.version()).isEqualTo(2);
        assertThat(jdbc.sql("""
                        SELECT decision_type
                        FROM recommendation_decisions
                        WHERE recommendation_id = :id
                          AND recommendation_version = 2
                        """)
                .param("id", purchase.id())
                .query(String.class)
                .single()).isEqualTo("EXPIRED");
    }

    private GovernedRecommendation recommendation(
            GenerationResult result,
            Type type
    ) {
        return result.recommendations().stream()
                .filter(value -> value.type() == type)
                .filter(value -> ingredientId.equals(value.ingredientId()))
                .findFirst()
                .orElseThrow();
    }

    private void insertLot(
            String id,
            BigDecimal quantity,
            LocalDate purchasedAt,
            LocalDate expiresAt,
            Instant createdAt
    ) {
        jdbc.sql("""
                        INSERT INTO stock_lots
                        (id, ingredient_id, quantity_remaining, unit,
                         purchased_at, expires_at, source, created_at,
                         kitchen_id, location_id, status, version,
                         expiry_provenance, source_quantity, source_unit)
                        VALUES
                        (:id, :ingredient, :quantity, 'g',
                         :purchasedAt, :expiresAt, 'demand-test', :createdAt,
                         :kitchen, :location, 'AVAILABLE', 1,
                         'OWNER_CONFIRMED', :quantity, 'g')
                        """)
                .param("id", id)
                .param("ingredient", ingredientId)
                .param("quantity", quantity)
                .param("purchasedAt", purchasedAt)
                .param("expiresAt", expiresAt)
                .param("createdAt", createdAt)
                .param("kitchen", KITCHEN)
                .param("location", LOCATION)
                .update();
    }

    private void insertMovement(
            String id,
            String locationId,
            String movementType,
            Instant occurredAt
    ) {
        jdbc.sql("""
                        INSERT INTO stock_movements
                        (id, ingredient_id, movement_type, quantity_change, unit,
                         reference_type, reference_id, occurred_at,
                         kitchen_id, location_id)
                        VALUES
                        (:id, :ingredient, :movementType, 1.000, 'g',
                         'inventory-count', :id, :occurredAt,
                         :kitchen, :location)
                        """)
                .param("id", id)
                .param("ingredient", ingredientId)
                .param("movementType", movementType)
                .param("occurredAt", occurredAt)
                .param("kitchen", KITCHEN)
                .param("location", locationId)
                .update();
    }
}
