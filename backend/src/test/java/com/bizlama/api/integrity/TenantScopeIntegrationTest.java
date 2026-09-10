package com.bizlama.api.integrity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bizlama.api.auth.WorkspaceAccessPolicy;
import com.bizlama.api.domain.Dish;
import com.bizlama.api.domain.Feedback;
import com.bizlama.api.domain.Order;
import com.bizlama.api.events.KitchenEventProposalService;
import com.bizlama.api.receipts.ReceiptQueryService;
import com.bizlama.api.receipts.ReceiptWorkflowService;
import com.bizlama.api.recommendations.RecommendationService;
import com.bizlama.api.store.OperationalRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@ActiveProfiles("memory")
@Transactional
class TenantScopeIntegrationTest {

    private static final String KITCHEN = "kitchen-default";
    private static final String LOCATION = "location-main";

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private WorkspaceAccessPolicy access;

    @Autowired
    private OperationalRepository repository;

    @Autowired
    private ReceiptQueryService receipts;

    @Autowired
    private ReceiptWorkflowService receiptWorkflow;

    @Autowired
    private KitchenEventProposalService eventProposals;

    @Autowired
    private RecommendationService recommendations;

    private String otherKitchen;
    private String otherCategory;
    private String otherLocation;
    private String otherIngredient;
    private String otherDish;
    private String otherRecipe;
    private String otherFeedback;
    private String otherExperiment;
    private String otherActivity;
    private String otherAlias;
    private String otherMovement;
    private String otherReceipt;
    private String otherOrder;
    private String otherEventProposal;
    private String otherRecommendation;
    private String memberSubject;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString();
        otherKitchen = "scope-kitchen-" + suffix;
        otherCategory = "scope-category-" + suffix;
        otherLocation = "scope-location-" + suffix;
        otherIngredient = "scope-ingredient-" + suffix;
        otherDish = "scope-dish-" + suffix;
        otherRecipe = "scope-recipe-" + suffix;
        otherFeedback = "scope-feedback-" + suffix;
        otherExperiment = "scope-experiment-" + suffix;
        otherActivity = "scope-activity-" + suffix;
        otherAlias = "scope-alias-" + suffix;
        otherMovement = "scope-movement-" + suffix;
        otherReceipt = "scope-receipt-" + suffix;
        otherOrder = "scope-order-" + suffix;
        otherEventProposal = "scope-event-proposal-" + suffix;
        otherRecommendation = "scope-recommendation-" + suffix;
        memberSubject = "scope-member-" + suffix;

        Instant now = Instant.now();
        jdbc.sql("""
                        INSERT INTO kitchens (id, name, timezone, currency, active)
                        VALUES (:id, 'Other tenant', 'UTC', 'USD', TRUE)
                        """)
                .param("id", otherKitchen)
                .update();
        jdbc.sql("""
                        INSERT INTO kitchen_locations
                        (id, kitchen_id, name, location_type, active)
                        VALUES (:id, :kitchen, 'Other location', 'KITCHEN', TRUE)
                        """)
                .param("id", otherLocation)
                .param("kitchen", otherKitchen)
                .update();
        jdbc.sql("""
                        INSERT INTO catalog_categories (id, kitchen_id, name)
                        VALUES (:id, :kitchen, 'Other category')
                        """)
                .param("id", otherCategory)
                .param("kitchen", otherKitchen)
                .update();
        jdbc.sql("""
                        INSERT INTO business_users
                        (id, kitchen_id, identity_subject, email,
                         display_name, role, active)
                        VALUES (:id, :kitchen, :subject, :email,
                                'Scope member', 'OWNER', TRUE)
                        """)
                .param("id", "scope-member-id-" + suffix)
                .param("kitchen", KITCHEN)
                .param("subject", memberSubject)
                .param("email", memberSubject + "@example.test")
                .update();
        jdbc.sql("""
                        INSERT INTO ingredients
                        (id, name, base_unit, active, kitchen_id)
                        VALUES (:id, 'Other ingredient', 'g', TRUE, :kitchen)
                        """)
                .param("id", otherIngredient)
                .param("kitchen", otherKitchen)
                .update();
        jdbc.sql("""
                        INSERT INTO ingredient_aliases
                        (alias_normalized, ingredient_id, display_name,
                         confidence, source, kitchen_id)
                        VALUES (:alias, :ingredient, 'Other alias',
                                1.0000, 'TEST', :kitchen)
                        """)
                .param("alias", otherAlias)
                .param("ingredient", otherIngredient)
                .param("kitchen", otherKitchen)
                .update();
        jdbc.sql("""
                        INSERT INTO dishes
                        (id, name, price, active, kitchen_id)
                        VALUES (:id, 'Other dish', 12.00, TRUE, :kitchen)
                        """)
                .param("id", otherDish)
                .param("kitchen", otherKitchen)
                .update();
        jdbc.sql("""
                        INSERT INTO recipe_versions
                        (id, dish_id, version_number, change_reason, active,
                         created_at, yield_quantity, yield_unit, kitchen_id)
                        VALUES (:id, :dish, 1, 'Other tenant recipe', FALSE,
                                :now, 1.000, 'each', :kitchen)
                        """)
                .param("id", otherRecipe)
                .param("dish", otherDish)
                .param("now", now)
                .param("kitchen", otherKitchen)
                .update();
        jdbc.sql("""
                        INSERT INTO recipe_ingredients
                        (recipe_version_id, ingredient_id, quantity, unit, kitchen_id)
                        VALUES (:recipe, :ingredient, 10.000, 'g', :kitchen)
                        """)
                .param("recipe", otherRecipe)
                .param("ingredient", otherIngredient)
                .param("kitchen", otherKitchen)
                .update();
        jdbc.sql("""
                        INSERT INTO recipe_steps
                        (recipe_version_id, step_number, instruction, kitchen_id)
                        VALUES (:recipe, 1, 'Other instruction', :kitchen)
                        """)
                .param("recipe", otherRecipe)
                .param("kitchen", otherKitchen)
                .update();
        jdbc.sql("""
                        INSERT INTO feedback
                        (id, recipe_id, feedback_text, rating,
                         occurred_at, source, kitchen_id)
                        VALUES (:id, :recipe, 'Foreign feedback', 5,
                                CURRENT_DATE, 'TEST', :kitchen)
                        """)
                .param("id", otherFeedback)
                .param("recipe", otherRecipe)
                .param("kitchen", otherKitchen)
                .update();
        jdbc.sql("""
                        INSERT INTO recipe_experiments
                        (id, dish_id, theme, theme_count, feedback_count,
                         current_value, proposed_value, test_duration_days,
                         status, metric_name, value_unit, recipe_version_id,
                         version, created_at, updated_at, created_by, kitchen_id)
                        VALUES
                        (:id, :dish, 'Foreign theme', 1, 1,
                         1.000, 2.000, 3,
                         'PROPOSED', 'Foreign metric', 'g', :recipe,
                         1, :now, :now, 'test', :kitchen)
                        """)
                .param("id", otherExperiment)
                .param("dish", otherDish)
                .param("recipe", otherRecipe)
                .param("now", now)
                .param("kitchen", otherKitchen)
                .update();
        jdbc.sql("""
                        INSERT INTO activity_events
                        (id, event_type, description, occurred_at,
                         kitchen_id, location_id)
                        VALUES (:id, 'Foreign activity', 'Must stay hidden', :now,
                                :kitchen, :location)
                        """)
                .param("id", otherActivity)
                .param("now", now)
                .param("kitchen", otherKitchen)
                .param("location", otherLocation)
                .update();

        String otherLot = "scope-lot-" + suffix;
        jdbc.sql("""
                        INSERT INTO stock_lots
                        (id, ingredient_id, quantity_remaining, unit,
                         purchased_at, expires_at, source, kitchen_id, location_id,
                         status, version, expiry_provenance,
                         source_quantity, source_unit)
                        VALUES
                        (:id, :ingredient, 10.000, 'g',
                         CURRENT_DATE, CURRENT_DATE + 1, 'TEST', :kitchen, :location,
                         'AVAILABLE', 1, 'OWNER_CONFIRMED', 10.000, 'g')
                        """)
                .param("id", otherLot)
                .param("ingredient", otherIngredient)
                .param("kitchen", otherKitchen)
                .param("location", otherLocation)
                .update();
        jdbc.sql("""
                        INSERT INTO stock_movements
                        (id, stock_lot_id, ingredient_id, movement_type,
                         quantity_change, unit, reference_type, reference_id,
                         occurred_at, kitchen_id, location_id)
                        VALUES
                        (:id, :lot, :ingredient, 'PURCHASE',
                         10.000, 'g', 'TEST', :id,
                         :now, :kitchen, :location)
                        """)
                .param("id", otherMovement)
                .param("lot", otherLot)
                .param("ingredient", otherIngredient)
                .param("now", now)
                .param("kitchen", otherKitchen)
                .param("location", otherLocation)
                .update();
        jdbc.sql("""
                        INSERT INTO receipt_imports
                        (id, original_filename, object_uri, status,
                         created_at, kitchen_id, location_id, version, updated_at)
                        VALUES
                        (:id, 'foreign.pdf', 'private://foreign', 'REVIEW_REQUIRED',
                         :now, :kitchen, :location, 1, :now)
                        """)
                .param("id", otherReceipt)
                .param("now", now)
                .param("kitchen", otherKitchen)
                .param("location", otherLocation)
                .update();
        jdbc.sql("""
                        INSERT INTO customer_orders
                        (id, total, status, created_at, required_at,
                         kitchen_id, location_id)
                        VALUES
                        (:id, 12.00, 'QUEUED', :now, :now,
                         :kitchen, :location)
                        """)
                .param("id", otherOrder)
                .param("now", now)
                .param("kitchen", otherKitchen)
                .param("location", otherLocation)
                .update();
        jdbc.sql("""
                        INSERT INTO kitchen_event_proposals
                        (id, kitchen_id, location_id, version, original_input,
                         events_json, risk_tier, status, expires_at, created_at)
                        VALUES
                        (:id, :kitchen, :location, 1, 'foreign', '[]',
                         'HIGH', 'PENDING', :expires, :now)
                        """)
                .param("id", otherEventProposal)
                .param("kitchen", otherKitchen)
                .param("location", otherLocation)
                .param("expires", now.plusSeconds(900))
                .param("now", now)
                .update();

        String calculation = "scope-calculation-" + suffix;
        String hash = "a".repeat(64);
        jdbc.sql("""
                        INSERT INTO demand_calculation_snapshots
                        (id, kitchen_id, location_id, horizon_start, horizon_end,
                         as_of, schema_version, calculation_method, payload_json,
                         payload_sha256, calculated_at, created_by)
                        VALUES
                        (:id, :kitchen, :location, :start, :end,
                         :asOf, 1, 'TEST', '{}', :hash, :asOf, 'test')
                        """)
                .param("id", calculation)
                .param("kitchen", otherKitchen)
                .param("location", otherLocation)
                .param("start", now.minusSeconds(10_800))
                .param("end", now.plusSeconds(3_600))
                .param("asOf", now.minusSeconds(7_200))
                .param("hash", hash)
                .update();
        jdbc.sql("""
                        INSERT INTO recommendations
                        (id, kitchen_id, location_id, recommendation_type,
                         ingredient_id, proposed_quantity, unit, calculation_id,
                         calculation_schema_version, confidence,
                         confidence_components_json, reason_code, risk_tier,
                         status, version, expires_at, created_at, created_by, updated_at)
                        VALUES
                        (:id, :kitchen, :location, 'PURCHASE',
                         :ingredient, 5.000, 'g', :calculation,
                         1, 1.0000, '{}', 'TEST', 'LOW',
                         'PENDING', 1, :expires, :created, 'test', :created)
                        """)
                .param("id", otherRecommendation)
                .param("kitchen", otherKitchen)
                .param("location", otherLocation)
                .param("ingredient", otherIngredient)
                .param("calculation", calculation)
                .param("created", now.minusSeconds(7_200))
                .param("expires", now.minusSeconds(3_600))
                .update();
    }

    @Test
    void activeConfiguredMemberCannotReadAnotherKitchenResources() {
        assertActiveConfiguredMember();

        assertThat(repository.menuCategory(otherCategory)).isEmpty();
        assertThat(repository.menuCategories())
                .noneMatch(value -> value.id().equals(otherCategory));
        assertThat(repository.dish(otherDish)).isEmpty();
        assertThat(repository.dishes()).noneMatch(value -> value.id().equals(otherDish));
        assertThat(repository.recipe(otherRecipe)).isEmpty();
        assertThat(repository.recipes()).noneMatch(value -> value.id().equals(otherRecipe));
        assertThat(repository.feedback(null))
                .noneMatch(value -> value.id().equals(otherFeedback));
        assertThat(repository.feedback(otherRecipe)).isEmpty();
        assertThat(repository.activities())
                .noneMatch(value -> value.id().equals(otherActivity));
        assertThat(repository.aliases())
                .noneMatch(value -> value.alias().equals(otherAlias));
        assertThat(repository.stockMovements())
                .noneMatch(value -> value.id().equals(otherMovement));
        assertThat(repository.receipt(otherReceipt)).isEmpty();
        assertThat(repository.receipts())
                .noneMatch(value -> value.id().equals(otherReceipt));
        assertThat(receipts.find(otherReceipt)).isEmpty();
        assertThat(receipts.list())
                .noneMatch(value -> value.id().equals(otherReceipt));
        assertThatThrownBy(() -> repository.experiment(otherExperiment))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Experiment not found");

        assertThatThrownBy(() -> recommendations.recommendation(otherRecommendation))
                .hasMessageContaining("Recommendation not found");
        assertThat(text("recommendations", "status", "id", otherRecommendation))
                .isEqualTo("PENDING");
    }

    @Test
    void activeConfiguredMemberCannotUpdateOrDeleteAnotherKitchenResources() {
        assertActiveConfiguredMember();

        repository.deleteIngredient(otherIngredient);
        repository.deleteDish(otherDish);
        repository.deleteFeedback(otherFeedback);
        assertThat(flag("ingredients", "active", "id", otherIngredient)).isTrue();
        assertThat(flag("dishes", "active", "id", otherDish)).isTrue();
        assertThat(count("feedback", "id", otherFeedback)).isOne();

        assertThatThrownBy(() -> repository.approveExperiment(otherExperiment))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Experiment not found");
        assertThat(text("recipe_experiments", "status", "id", otherExperiment))
                .isEqualTo("PROPOSED");

        assertThatThrownBy(() -> repository.activateRecipe(otherRecipe, memberSubject))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Recipe version not found");
        assertThat(flag("recipe_versions", "active", "id", otherRecipe)).isFalse();

        assertThatThrownBy(() -> repository.updateOrderStatus(
                otherOrder,
                Order.Status.DONE
        )).isInstanceOf(IllegalArgumentException.class);
        assertThat(text("customer_orders", "status", "id", otherOrder))
                .isEqualTo("QUEUED");

        assertThatThrownBy(() -> receiptWorkflow.addManualLine(
                otherReceipt,
                1,
                new ReceiptWorkflowService.ManualLine(
                        "Foreign line",
                        BigDecimal.ONE,
                        "g",
                        BigDecimal.ONE
                )
        )).isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(404);
        assertThat(count("receipt_items", "receipt_id", otherReceipt)).isZero();

        assertThatThrownBy(() -> eventProposals.supersede(
                otherEventProposal,
                1,
                memberSubject
        )).isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(404);
        assertThat(text(
                "kitchen_event_proposals",
                "status",
                "id",
                otherEventProposal
        )).isEqualTo("PENDING");

        assertThatThrownBy(() -> recommendations.dismiss(
                otherRecommendation,
                1,
                memberSubject,
                "Must not mutate another tenant"
        )).hasMessageContaining("Recommendation not found");
        assertThat(text(
                "recommendations",
                "status",
                "id",
                otherRecommendation
        )).isEqualTo("PENDING");

        Feedback attempted = new Feedback(
                "scope-forbidden-feedback-" + UUID.randomUUID(),
                otherRecipe,
                "Must not persist",
                5,
                LocalDate.now(),
                "TEST"
        );
        assertThatThrownBy(() -> repository.saveFeedback(attempted))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found in this kitchen");
        assertThat(count("feedback", "id", attempted.id())).isZero();

        assertThatThrownBy(() -> repository.saveDish(new Dish(
                "scope-forbidden-dish-" + UUID.randomUUID(),
                "Invalid scoped dish",
                BigDecimal.TEN,
                null,
                true,
                otherCategory,
                null
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found in this kitchen");
    }

    @Test
    void configuredKitchenCannotCrossItsConfiguredLocationBoundary() {
        assertActiveConfiguredMember();
        String suffix = UUID.randomUUID().toString();
        String adjacentLocation = "scope-adjacent-location-" + suffix;
        String activity = "scope-adjacent-activity-" + suffix;
        String lot = "scope-adjacent-lot-" + suffix;
        String movement = "scope-adjacent-movement-" + suffix;
        String receipt = "scope-adjacent-receipt-" + suffix;
        String order = "scope-adjacent-order-" + suffix;
        String proposal = "scope-adjacent-proposal-" + suffix;
        String calculation = "scope-adjacent-calculation-" + suffix;
        String recommendation = "scope-adjacent-recommendation-" + suffix;
        Instant now = Instant.now();

        jdbc.sql("""
                        INSERT INTO kitchen_locations
                        (id, kitchen_id, name, location_type, active)
                        VALUES (:id, :kitchen, 'Adjacent location', 'KITCHEN', TRUE)
                        """)
                .param("id", adjacentLocation)
                .param("kitchen", KITCHEN)
                .update();
        jdbc.sql("""
                        INSERT INTO activity_events
                        (id, event_type, description, occurred_at,
                         kitchen_id, location_id)
                        VALUES (:id, 'Adjacent activity', 'Must stay hidden', :now,
                                :kitchen, :location)
                        """)
                .param("id", activity)
                .param("now", now)
                .param("kitchen", KITCHEN)
                .param("location", adjacentLocation)
                .update();
        jdbc.sql("""
                        INSERT INTO stock_lots
                        (id, ingredient_id, quantity_remaining, unit,
                         purchased_at, expires_at, source, kitchen_id, location_id,
                         status, version, expiry_provenance,
                         source_quantity, source_unit)
                        VALUES
                        (:id, 'paneer', 3.000, 'g', CURRENT_DATE,
                         CURRENT_DATE + 1, 'TEST', :kitchen, :location,
                         'AVAILABLE', 1, 'OWNER_CONFIRMED', 3.000, 'g')
                        """)
                .param("id", lot)
                .param("kitchen", KITCHEN)
                .param("location", adjacentLocation)
                .update();
        jdbc.sql("""
                        INSERT INTO stock_movements
                        (id, stock_lot_id, ingredient_id, movement_type,
                         quantity_change, unit, reference_type, reference_id,
                         occurred_at, kitchen_id, location_id)
                        VALUES
                        (:id, :lot, 'paneer', 'PURCHASE', 3.000, 'g',
                         'TEST', :id, :now, :kitchen, :location)
                        """)
                .param("id", movement)
                .param("lot", lot)
                .param("now", now)
                .param("kitchen", KITCHEN)
                .param("location", adjacentLocation)
                .update();
        jdbc.sql("""
                        INSERT INTO receipt_imports
                        (id, original_filename, object_uri, status,
                         created_at, kitchen_id, location_id, version, updated_at)
                        VALUES
                        (:id, 'adjacent.pdf', 'private://adjacent', 'REVIEW_REQUIRED',
                         :now, :kitchen, :location, 1, :now)
                        """)
                .param("id", receipt)
                .param("now", now)
                .param("kitchen", KITCHEN)
                .param("location", adjacentLocation)
                .update();
        jdbc.sql("""
                        INSERT INTO customer_orders
                        (id, total, status, created_at, required_at,
                         kitchen_id, location_id)
                        VALUES (:id, 3.00, 'QUEUED', :now, :now,
                                :kitchen, :location)
                        """)
                .param("id", order)
                .param("now", now)
                .param("kitchen", KITCHEN)
                .param("location", adjacentLocation)
                .update();
        jdbc.sql("""
                        INSERT INTO kitchen_event_proposals
                        (id, kitchen_id, location_id, version, original_input,
                         events_json, risk_tier, status, expires_at, created_at)
                        VALUES
                        (:id, :kitchen, :location, 1, 'adjacent', '[]',
                         'HIGH', 'PENDING', :expires, :now)
                        """)
                .param("id", proposal)
                .param("kitchen", KITCHEN)
                .param("location", adjacentLocation)
                .param("expires", now.plusSeconds(900))
                .param("now", now)
                .update();
        jdbc.sql("""
                        INSERT INTO demand_calculation_snapshots
                        (id, kitchen_id, location_id, horizon_start, horizon_end,
                         as_of, schema_version, calculation_method, payload_json,
                         payload_sha256, calculated_at, created_by)
                        VALUES
                        (:id, :kitchen, :location, :start, :end,
                         :asOf, 1, 'TEST', '{}', :hash, :asOf, 'test')
                        """)
                .param("id", calculation)
                .param("kitchen", KITCHEN)
                .param("location", adjacentLocation)
                .param("start", now.minusSeconds(10_800))
                .param("end", now.plusSeconds(3_600))
                .param("asOf", now.minusSeconds(7_200))
                .param("hash", "b".repeat(64))
                .update();
        jdbc.sql("""
                        INSERT INTO recommendations
                        (id, kitchen_id, location_id, recommendation_type,
                         ingredient_id, proposed_quantity, unit, calculation_id,
                         calculation_schema_version, confidence,
                         confidence_components_json, reason_code, risk_tier,
                         status, version, expires_at, created_at, created_by, updated_at)
                        VALUES
                        (:id, :kitchen, :location, 'PURCHASE',
                         'paneer', 2.000, 'g', :calculation,
                         1, 1.0000, '{}', 'TEST', 'LOW',
                         'PENDING', 1, :expires, :created, 'test', :created)
                        """)
                .param("id", recommendation)
                .param("kitchen", KITCHEN)
                .param("location", adjacentLocation)
                .param("calculation", calculation)
                .param("created", now.minusSeconds(7_200))
                .param("expires", now.minusSeconds(3_600))
                .update();

        assertThat(repository.activities())
                .noneMatch(value -> value.id().equals(activity));
        assertThat(repository.stockLots())
                .noneMatch(value -> value.id().equals(lot));
        assertThat(repository.stockMovements())
                .noneMatch(value -> value.id().equals(movement));
        assertThat(receipts.find(receipt)).isEmpty();
        assertThat(repository.order(order)).isEmpty();
        assertThatThrownBy(() -> recommendations.recommendation(recommendation))
                .hasMessageContaining("Recommendation not found");
        assertThat(text("recommendations", "status", "id", recommendation))
                .isEqualTo("PENDING");

        assertThatThrownBy(() -> repository.updateOrderStatus(
                order,
                Order.Status.DONE
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> receiptWorkflow.addManualLine(
                receipt,
                1,
                new ReceiptWorkflowService.ManualLine(
                        "Adjacent line",
                        BigDecimal.ONE,
                        "g",
                        BigDecimal.ONE
                )
        )).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> eventProposals.supersede(
                proposal,
                1,
                memberSubject
        )).isInstanceOf(ResponseStatusException.class);
        assertThat(text("customer_orders", "status", "id", order))
                .isEqualTo("QUEUED");
        assertThat(count("receipt_items", "receipt_id", receipt)).isZero();
        assertThat(text("kitchen_event_proposals", "status", "id", proposal))
                .isEqualTo("PENDING");
    }

    @Test
    void migrationBackfillsLegacyRowsAndDatabaseRejectsCrossKitchenLinks() {
        assertThat(jdbc.sql("""
                        SELECT COUNT(*)
                        FROM recipe_versions recipe
                        JOIN dishes dish ON dish.id = recipe.dish_id
                        WHERE recipe.kitchen_id <> dish.kitchen_id
                        """)
                .query(Long.class)
                .single()).isZero();
        assertThat(jdbc.sql("""
                        SELECT COUNT(*)
                        FROM activity_events
                        WHERE kitchen_id IS NULL OR location_id IS NULL
                        """)
                .query(Long.class)
                .single()).isZero();

        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO feedback
                        (id, recipe_id, feedback_text, rating,
                         occurred_at, source, kitchen_id)
                        VALUES (:id, :recipe, 'Cross tenant', 5,
                                CURRENT_DATE, 'TEST', :kitchen)
                        """)
                .param("id", "scope-invalid-feedback-" + UUID.randomUUID())
                .param("recipe", otherRecipe)
                .param("kitchen", KITCHEN)
                .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("fk_feedback_recipe_same_kitchen");

        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO activity_events
                        (id, event_type, description, occurred_at,
                         kitchen_id, location_id)
                        VALUES (:id, 'Invalid', 'Cross tenant location', CURRENT_TIMESTAMP,
                                :kitchen, :location)
                        """)
                .param("id", "scope-invalid-activity-" + UUID.randomUUID())
                .param("kitchen", KITCHEN)
                .param("location", otherLocation)
                .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("fk_activity_events_scope");

        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO recipe_ingredients
                        (recipe_version_id, ingredient_id, quantity, unit, kitchen_id)
                        VALUES (:recipe, :ingredient, 1.000, 'g', :kitchen)
                        """)
                .param("recipe", otherRecipe)
                .param("ingredient", "paneer")
                .param("kitchen", KITCHEN)
                .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining(
                        "fk_recipe_ingredients_recipe_same_kitchen"
                );

        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO catalog_categories
                        (id, kitchen_id, name, parent_id)
                        VALUES (:id, :kitchen, 'Invalid child', :parent)
                        """)
                .param("id", "scope-invalid-category-" + UUID.randomUUID())
                .param("kitchen", KITCHEN)
                .param("parent", otherCategory)
                .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining(
                        "fk_catalog_categories_parent_same_kitchen"
                );
    }

    private void assertActiveConfiguredMember() {
        Jwt jwt = Jwt.withTokenValue("scope-test")
                .header("alg", "none")
                .subject(memberSubject)
                .claim("iss", "https://securetoken.google.com/scope-test")
                .claim("role", "OWNER")
                .build();
        assertThat(access.authorizeConfiguredWorkspace(jwt)).isEqualTo(KITCHEN);
    }

    private long count(String table, String column, String value) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = :value")
                .param("value", value)
                .query(Long.class)
                .single();
    }

    private boolean flag(String table, String column, String key, String value) {
        return jdbc.sql("SELECT " + column + " FROM " + table + " WHERE " + key + " = :value")
                .param("value", value)
                .query(Boolean.class)
                .single();
    }

    private String text(String table, String column, String key, String value) {
        return jdbc.sql("SELECT " + column + " FROM " + table + " WHERE " + key + " = :value")
                .param("value", value)
                .query(String.class)
                .single();
    }
}
