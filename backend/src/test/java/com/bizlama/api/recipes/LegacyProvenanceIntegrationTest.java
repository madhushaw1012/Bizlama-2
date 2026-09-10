package com.bizlama.api.recipes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bizlama.api.recommendations.DemandCalculation;
import com.bizlama.api.recommendations.DemandCalculation.DemandScope;
import com.bizlama.api.recommendations.DemandCalculation.IngredientDemand;
import com.bizlama.api.recommendations.DemandValidationException;
import com.bizlama.api.recommendations.GovernedRecommendation;
import com.bizlama.api.recommendations.RecommendationService;
import com.bizlama.api.recommendations.DemandCalculationService;
import java.math.BigDecimal;
import java.time.Instant;
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
class LegacyProvenanceIntegrationTest {

    private static final String KITCHEN = "kitchen-default";
    private static final String LOCATION = "location-main";

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DemandCalculationService demand;

    @Autowired
    private RecommendationService recommendations;

    @Autowired
    private LegacyProvenanceReviewService reviews;

    private String ingredientOne;
    private String ingredientTwo;
    private String dishId;
    private String recipeOne;
    private String recipeTwo;
    private String orderId;
    private DemandScope scope;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString();
        ingredientOne = "legacy-provenance-one-" + suffix;
        ingredientTwo = "legacy-provenance-two-" + suffix;
        dishId = "legacy-provenance-dish-" + suffix;
        recipeOne = "legacy-provenance-v1-" + suffix;
        recipeTwo = "legacy-provenance-v2-" + suffix;
        orderId = "legacy-provenance-order-" + suffix;

        Instant now = Instant.now();
        Instant recipeTime = now.minusSeconds(180);
        Instant orderTime = now.minusSeconds(60);
        Instant requiredAt = now.plusSeconds(24 * 60 * 60);
        scope = new DemandScope(
                KITCHEN,
                LOCATION,
                now.minusSeconds(60 * 60),
                now.plusSeconds(2 * 24 * 60 * 60),
                now
        );

        jdbc.sql("""
                        INSERT INTO ingredients
                        (id, name, base_unit, active, created_at, kitchen_id)
                        VALUES
                        (:one, 'Legacy ingredient one', 'g', TRUE, :created, :kitchen),
                        (:two, 'Legacy ingredient two', 'g', TRUE, :created, :kitchen)
                        """)
                .param("one", ingredientOne)
                .param("two", ingredientTwo)
                .param("created", recipeTime.minusSeconds(60))
                .param("kitchen", KITCHEN)
                .update();
        jdbc.sql("""
                        INSERT INTO dishes
                        (id, name, price, active, created_at, kitchen_id)
                        VALUES
                        (:id, 'Legacy provenance dish', 10.00, TRUE, :created, :kitchen)
                        """)
                .param("id", dishId)
                .param("created", recipeTime.minusSeconds(60))
                .param("kitchen", KITCHEN)
                .update();
        jdbc.sql("""
                        INSERT INTO recipe_versions
                        (id, dish_id, version_number, change_reason, active,
                         created_at, yield_quantity, yield_unit, yield_provenance,
                         kitchen_id)
                        VALUES
                        (:one, :dish, 1, 'Legacy candidate one', FALSE,
                         :created, 1.000, 'each', 'LEGACY_PER_ITEM_SCHEMA', :kitchen),
                        (:two, :dish, 2, 'Legacy candidate two', FALSE,
                         :created, 1.000, 'each', 'LEGACY_PER_ITEM_SCHEMA', :kitchen)
                        """)
                .param("one", recipeOne)
                .param("two", recipeTwo)
                .param("dish", dishId)
                .param("created", recipeTime)
                .param("kitchen", KITCHEN)
                .update();
        jdbc.sql("""
                        INSERT INTO recipe_ingredients
                        (recipe_version_id, ingredient_id, quantity, unit, kitchen_id)
                        VALUES
                        (:recipeOne, :ingredientOne, 10.000, 'g', :kitchen),
                        (:recipeTwo, :ingredientTwo, 20.000, 'g', :kitchen)
                        """)
                .param("recipeOne", recipeOne)
                .param("ingredientOne", ingredientOne)
                .param("recipeTwo", recipeTwo)
                .param("ingredientTwo", ingredientTwo)
                .param("kitchen", KITCHEN)
                .update();
        jdbc.sql("""
                        INSERT INTO customer_orders
                        (id, total, status, created_at, required_at,
                         kitchen_id, location_id)
                        VALUES
                        (:id, 20.00, 'QUEUED', :created, :required,
                         :kitchen, :location)
                        """)
                .param("id", orderId)
                .param("created", orderTime)
                .param("required", requiredAt)
                .param("kitchen", KITCHEN)
                .param("location", LOCATION)
                .update();
        jdbc.sql("""
                        INSERT INTO order_items
                        (order_id, line_number, dish_id, recipe_version_id,
                         recipe_pin_provenance, quantity, unit_price)
                        VALUES
                        (:orderId, 1, :dish, :recipe,
                         'LEGACY_RECONSTRUCTED', 2, 10.00)
                        """)
                .param("orderId", orderId)
                .param("dish", dishId)
                .param("recipe", recipeOne)
                .update();

        demand.configureSafetyStock(
                KITCHEN,
                LOCATION,
                ingredientOne,
                new BigDecimal("5"),
                "g",
                null,
                "test-owner"
        );
        demand.configureSafetyStock(
                KITCHEN,
                LOCATION,
                ingredientTwo,
                new BigDecimal("5"),
                "g",
                null,
                "test-owner"
        );
    }

    @Test
    void ambiguousLegacyPinIsVisibleButCannotDriveGovernedAdvice() {
        DemandCalculation calculation = demand.calculate(scope);

        assertThat(calculation.unresolvedOrderEvidence())
                .filteredOn(value -> value.orderId().equals(orderId))
                .singleElement()
                .satisfies(value -> {
                    assertThat(value.orderId()).isEqualTo(orderId);
                    assertThat(value.provisionalRecipeVersionId())
                            .isEqualTo(recipeOne);
                    assertThat(value.recipePinProvenance())
                            .isEqualTo("LEGACY_RECONSTRUCTED");
                    assertThat(value.recipeYieldProvenance())
                            .isEqualTo("LEGACY_PER_ITEM_SCHEMA");
                    assertThat(value.potentiallyAffectedIngredientIds())
                            .containsExactly(ingredientOne, ingredientTwo);
                    assertThat(value.resolutionCode())
                            .isEqualTo("OWNER_RECIPE_PIN_AND_YIELD_REVIEW_REQUIRED");
                });
        assertThat(ingredient(calculation, ingredientOne).grossDemand())
                .isEqualByComparingTo("0");
        assertThat(ingredient(calculation, ingredientOne).demandEvidenceComplete())
                .isFalse();
        assertThat(ingredient(calculation, ingredientTwo).demandEvidenceComplete())
                .isFalse();
        assertThat(calculation.preparations())
                .noneMatch(value -> value.dishId().equals(dishId));

        var generated = recommendations.generate(
                scope,
                Instant.now().plusSeconds(60 * 60),
                "test-owner"
        );
        assertThat(generated.recommendations())
                .noneMatch(value -> ingredientOne.equals(value.ingredientId())
                        || ingredientTwo.equals(value.ingredientId())
                        || dishId.equals(value.dishId()));
    }

    @Test
    void ownerAttestationUnlocksExactDemandAndRetainsAudit() {
        assertThat(reviews.reviewQueue().orderRecipePins())
                .anyMatch(value -> value.orderId().equals(orderId));

        assertThatThrownBy(() -> reviews.confirmOrderRecipePin(
                orderId,
                1,
                recipeTwo,
                "External records identify another recipe.",
                "test-owner"
        )).isInstanceOf(DemandValidationException.class);

        var pinResult = reviews.confirmOrderRecipePin(
                orderId,
                1,
                recipeOne,
                "Checked against the retained order ticket.",
                "test-owner"
        );
        assertThat(pinResult.changed()).isTrue();
        assertThat(pinResult.provenance()).isEqualTo("OWNER_CONFIRMED");

        var retry = reviews.confirmOrderRecipePin(
                orderId,
                1,
                recipeOne,
                "Idempotent retry.",
                "test-owner"
        );
        assertThat(retry.changed()).isFalse();

        DemandCalculation yieldBlocked = demand.calculate(scope);
        assertThat(yieldBlocked.unresolvedOrderEvidence())
                .filteredOn(value -> value.orderId().equals(orderId))
                .singleElement()
                .satisfies(value -> {
                    assertThat(value.orderId()).isEqualTo(orderId);
                    assertThat(value.recipePinProvenance())
                            .isEqualTo("OWNER_CONFIRMED");
                    assertThat(value.recipeYieldProvenance())
                            .isEqualTo("LEGACY_PER_ITEM_SCHEMA");
                    assertThat(value.potentiallyAffectedIngredientIds())
                            .containsExactly(ingredientOne);
                    assertThat(value.resolutionCode())
                            .isEqualTo("OWNER_RECIPE_YIELD_REVIEW_REQUIRED");
                });
        assertThat(ingredient(yieldBlocked, ingredientOne).grossDemand())
                .isEqualByComparingTo("0");
        assertThat(ingredient(yieldBlocked, ingredientOne).demandEvidenceComplete())
                .isFalse();

        var yieldBlockedAdvice = recommendations.generate(
                scope,
                Instant.now().plusSeconds(60 * 60),
                "test-owner"
        );
        assertThat(yieldBlockedAdvice.recommendations())
                .noneMatch(value -> ingredientOne.equals(value.ingredientId())
                        || dishId.equals(value.dishId()));

        assertThatThrownBy(() -> reviews.confirmLegacyYield(
                recipeOne,
                new BigDecimal("2"),
                "each",
                "A changed yield must become a new recipe version.",
                "test-owner"
        )).isInstanceOf(DemandValidationException.class);

        var yieldResult = reviews.confirmLegacyYield(
                recipeOne,
                BigDecimal.ONE,
                "each",
                "Confirmed the legacy per-item recipe convention.",
                "test-owner"
        );
        assertThat(yieldResult.changed()).isTrue();

        DemandCalculation calculation = demand.calculate(scope);
        assertThat(calculation.unresolvedOrderEvidence())
                .noneMatch(value -> value.orderId().equals(orderId));
        IngredientDemand result = ingredient(calculation, ingredientOne);
        assertThat(result.demandEvidenceComplete()).isTrue();
        assertThat(result.grossDemand()).isEqualByComparingTo("20");
        assertThat(result.orderContributions())
                .singleElement()
                .satisfies(value -> {
                    assertThat(value.recipeVersionId()).isEqualTo(recipeOne);
                    assertThat(value.recipePinProvenance())
                            .isEqualTo("OWNER_CONFIRMED");
                    assertThat(value.recipeYieldProvenance())
                            .isEqualTo("OWNER_CONFIRMED");
                });

        var generated = recommendations.generate(
                scope,
                Instant.now().plusSeconds(60 * 60),
                "test-owner"
        );
        assertThat(generated.recommendations())
                .anyMatch(value -> value.type()
                                == GovernedRecommendation.Type.PURCHASE
                        && ingredientOne.equals(value.ingredientId()));

        assertThat(jdbc.sql("""
                        SELECT COUNT(*)
                        FROM legacy_provenance_reviews
                        WHERE order_id = :orderId
                           OR recipe_version_id = :recipe
                        """)
                .param("orderId", orderId)
                .param("recipe", recipeOne)
                .query(Long.class)
                .single()).isEqualTo(2L);
        assertThat(jdbc.sql("""
                        SELECT COUNT(*)
                        FROM analytics_outbox
                        WHERE event_type IN (
                          'ORDER_RECIPE_PIN_CONFIRMED',
                          'RECIPE_YIELD_PROVENANCE_CONFIRMED'
                        )
                          AND correlation_id IN (:orderId, :reviewId)
                        """)
                .param("orderId", orderId)
                .param("reviewId", yieldResult.reviewId())
                .query(Long.class)
                .single()).isEqualTo(2L);
    }

    private IngredientDemand ingredient(
            DemandCalculation calculation,
            String id
    ) {
        return calculation.ingredients().stream()
                .filter(value -> value.ingredientId().equals(id))
                .findFirst()
                .orElseThrow();
    }
}
