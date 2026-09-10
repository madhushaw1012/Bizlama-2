package com.bizlama.api.recommendations;

import com.bizlama.api.config.JdbcTimestamp;
import com.bizlama.api.domain.Ingredient;
import com.bizlama.api.quantity.CanonicalQuantity;
import com.bizlama.api.quantity.IncompatibleUnitException;
import com.bizlama.api.quantity.UnitConversionService;
import com.bizlama.api.quantity.UnitDimension;
import com.bizlama.api.recommendations.DemandCalculation.DemandScope;
import com.bizlama.api.recommendations.DemandCalculation.ExcludedLotEvidence;
import com.bizlama.api.recommendations.DemandCalculation.IngredientDemand;
import com.bizlama.api.recommendations.DemandCalculation.LotEvidence;
import com.bizlama.api.recommendations.DemandCalculation.OrderDemandEvidence;
import com.bizlama.api.recommendations.DemandCalculation.PreparationDemand;
import com.bizlama.api.recommendations.DemandCalculation.SafetyStockSetting;
import com.bizlama.api.recommendations.DemandCalculation.UnresolvedOrderEvidence;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Deterministic demand, usable-supply, shortage, and expiry-risk calculation. */
@Service
public class DemandCalculationService {

    static final MathContext CALCULATION_CONTEXT = MathContext.DECIMAL128;
    private static final Duration MAX_HORIZON = Duration.ofDays(31);
    private static final Duration MAX_AS_OF_AGE = Duration.ofMinutes(5);
    private static final Duration MAX_FUTURE_CLOCK_SKEW = Duration.ofSeconds(5);
    private static final List<String> INCLUDED_ORDER_STATUSES =
            List.of("QUEUED");

    private final JdbcClient jdbc;
    private final UnitConversionService units;

    public DemandCalculationService(
            JdbcClient jdbc,
            UnitConversionService units
    ) {
        this.jdbc = jdbc;
        this.units = units;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DemandCalculation calculate(DemandScope scope) {
        ScopeContext context = validateScope(scope);
        Map<String, IngredientRow> ingredients = loadIngredients(scope.kitchenId());
        List<OrderLineRow> orderLines = loadOrderLines(scope);
        Map<OrderLineKey, Set<String>> potentiallyAffectedIngredients =
                loadPotentiallyAffectedIngredients(scope);
        Set<OrderLineKey> unresolvedLineKeys = orderLines.stream()
                .filter(line ->
                        !trustedRecipePin(line.recipePinProvenance())
                                || !trustedRecipeYield(line.recipeYieldProvenance()))
                .map(OrderLineRow::key)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<UnresolvedOrderEvidence> unresolvedOrderEvidence = orderLines.stream()
                .filter(line -> unresolvedLineKeys.contains(line.key()))
                .map(line -> new UnresolvedOrderEvidence(
                        line.orderId(),
                        line.lineNumber(),
                        line.dishId(),
                        line.recipeVersionId(),
                        line.recipePinProvenance(),
                        line.recipeYieldProvenance(),
                        line.quantity(),
                        line.preparedQuantity(),
                        line.orderedAt(),
                        line.requiredAt(),
                        List.copyOf(potentiallyAffectedIngredients.getOrDefault(
                                line.key(), Set.of())),
                        unresolvedResolutionCode(line)
                ))
                .toList();
        Set<String> incompleteDemandIngredientIds = potentiallyAffectedIngredients
                .values()
                .stream()
                .flatMap(Set::stream)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<IngredientContributionRow> rawContributions =
                loadIngredientContributions(scope);

        Map<String, List<OrderDemandEvidence>> contributions = new HashMap<>();
        Map<OrderLineKey, Integer> contributionCount = new HashMap<>();
        Map<String, Set<String>> ingredientsByRecipe = new HashMap<>();

        for (IngredientContributionRow row : rawContributions) {
            if (!trustedRecipePin(row.recipePinProvenance())
                    || !trustedRecipeYield(row.recipeYieldProvenance())) {
                continue;
            }
            IngredientRow ingredient = requireIngredient(
                    ingredients,
                    row.ingredientId()
            );
            if (!scope.kitchenId().equals(row.dishKitchenId())
                    || !scope.kitchenId().equals(row.ingredientKitchenId())) {
                throw new DemandValidationException(
                        "Order, dish, recipe, and ingredient must belong to the same kitchen."
                );
            }

            CanonicalQuantity recipeQuantity;
            CanonicalQuantity recipeYield;
            try {
                recipeQuantity = units.toIngredientBase(
                        ingredient.asIngredient(),
                        row.recipeIngredientQuantity(),
                        row.recipeIngredientUnit()
                );
                recipeYield = units.canonicalize(
                        row.yieldQuantity(),
                        row.yieldUnit()
                );
            } catch (IncompatibleUnitException error) {
                throw new DemandValidationException(
                        "Recipe " + row.recipeVersionId()
                                + " contains incompatible units: " + error.getMessage(),
                        error
                );
            }
            if (recipeYield.dimension() != UnitDimension.COUNT) {
                throw new DemandValidationException(
                        "Recipe " + row.recipeVersionId()
                                + " yield must use a count unit."
                );
            }

            BigDecimal canonicalDemand = recipeQuantity.quantity()
                    .multiply(BigDecimal.valueOf(row.remainingQuantity()), CALCULATION_CONTEXT)
                    .divide(recipeYield.quantity(), CALCULATION_CONTEXT);

            OrderDemandEvidence evidence = new OrderDemandEvidence(
                    row.orderId(),
                    row.lineNumber(),
                    row.dishId(),
                    row.recipeVersionId(),
                    row.recipePinProvenance(),
                    row.recipeYieldProvenance(),
                    row.orderedQuantity(),
                    row.preparedQuantity(),
                    row.remainingQuantity(),
                    row.orderedAt(),
                    row.requiredAt(),
                    clean(row.recipeIngredientQuantity()),
                    row.recipeIngredientUnit(),
                    clean(row.yieldQuantity()),
                    row.yieldUnit(),
                    clean(canonicalDemand),
                    recipeQuantity.unit()
            );
            contributions.computeIfAbsent(row.ingredientId(), ignored -> new ArrayList<>())
                    .add(evidence);
            contributionCount.merge(row.lineKey(), 1, Integer::sum);
            ingredientsByRecipe
                    .computeIfAbsent(row.recipeVersionId(), ignored -> new TreeSet<>())
                    .add(row.ingredientId());
        }

        for (OrderLineRow line : orderLines) {
            if (unresolvedLineKeys.contains(line.key())) {
                continue;
            }
            if (!scope.kitchenId().equals(line.dishKitchenId())) {
                throw new DemandValidationException(
                        "Order " + line.orderId() + " references a dish from another kitchen."
                );
            }
            if (!contributionCount.containsKey(line.key())) {
                throw new DemandValidationException(
                        "Recipe " + line.recipeVersionId()
                                + " has no ingredients and cannot produce demand."
                );
            }
        }

        Map<String, SafetyValue> safety = loadSafetyStock(scope, ingredients);
        LotLoadResult lotLoad = loadLots(scope, context, ingredients);

        Set<String> ingredientIds = new TreeSet<>();
        ingredientIds.addAll(contributions.keySet());
        ingredientIds.addAll(safety.keySet());
        ingredientIds.addAll(lotLoad.eligible().keySet());
        ingredientIds.addAll(lotLoad.excluded().keySet());
        ingredientIds.addAll(incompleteDemandIngredientIds);

        List<IngredientDemand> ingredientResults = new ArrayList<>();
        for (String ingredientId : ingredientIds) {
            IngredientRow ingredient = requireIngredient(ingredients, ingredientId);
            List<OrderDemandEvidence> ingredientContributions = new ArrayList<>(
                    contributions.getOrDefault(ingredientId, List.of())
            );
            ingredientContributions.sort(Comparator
                    .comparing(OrderDemandEvidence::requiredAt)
                    .thenComparing(OrderDemandEvidence::orderedAt)
                    .thenComparing(OrderDemandEvidence::orderId)
                    .thenComparingInt(OrderDemandEvidence::lineNumber));

            List<ProjectedLot> projectedLots = lotLoad.eligible().getOrDefault(
                    ingredientId,
                    List.of()
            ).stream().map(ProjectedLot::copy).toList();

            BigDecimal timePhasedShortfall = allocateProjectedDemand(
                    projectedLots,
                    ingredientContributions,
                    context.zone(),
                    context.asOfDate()
            );
            BigDecimal grossDemand = ingredientContributions.stream()
                    .map(OrderDemandEvidence::canonicalDemand)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal onHandUsableSupply = projectedLots.stream()
                    .map(ProjectedLot::originalQuantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal horizonEndSurvivingSupply = projectedLots.stream()
                    .filter(lot -> lot.expiresAt().isAfter(context.horizonEndDate()))
                    .map(ProjectedLot::remaining)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal coveredDemand = grossDemand.subtract(timePhasedShortfall);
            BigDecimal usableSupply = coveredDemand.add(horizonEndSurvivingSupply);
            SafetyValue safetyValue = safety.getOrDefault(
                    ingredientId,
                    SafetyValue.zero()
            );
            BigDecimal shortage = grossDemand
                    .add(safetyValue.quantity())
                    .subtract(usableSupply)
                    .max(BigDecimal.ZERO);

            List<LotEvidence> lotEvidence = new ArrayList<>();
            BigDecimal expiryRisk = BigDecimal.ZERO;
            for (ProjectedLot lot : projectedLots) {
                BigDecimal riskQuantity = lot.expiresAt().isAfter(context.horizonEndDate())
                        ? BigDecimal.ZERO
                        : lot.remaining();
                expiryRisk = expiryRisk.add(riskQuantity);
                lotEvidence.add(new LotEvidence(
                        lot.id(),
                        clean(lot.storedQuantity()),
                        lot.storedUnit(),
                        clean(lot.originalQuantity()),
                        ingredient.canonicalUnit(units),
                        lot.purchasedAt(),
                        lot.expiresAt(),
                        lot.expiryProvenance(),
                        clean(lot.originalQuantity().subtract(lot.remaining())),
                        clean(lot.remaining()),
                        clean(riskQuantity)
                ));
            }

            List<String> orderIds = ingredientContributions.stream()
                    .map(OrderDemandEvidence::orderId)
                    .collect(java.util.stream.Collectors.collectingAndThen(
                            java.util.stream.Collectors.toCollection(TreeSet::new),
                            List::copyOf
                    ));
            List<String> recipeVersionIds = ingredientContributions.stream()
                    .map(OrderDemandEvidence::recipeVersionId)
                    .collect(java.util.stream.Collectors.collectingAndThen(
                            java.util.stream.Collectors.toCollection(TreeSet::new),
                            List::copyOf
                    ));

            ingredientResults.add(new IngredientDemand(
                    ingredient.id(),
                    ingredient.name(),
                    ingredient.canonicalUnit(units),
                    clean(grossDemand),
                    clean(onHandUsableSupply),
                    clean(horizonEndSurvivingSupply),
                    clean(usableSupply),
                    clean(safetyValue.quantity()),
                    safetyValue.source(),
                    clean(shortage),
                    clean(timePhasedShortfall),
                    clean(expiryRisk),
                    !incompleteDemandIngredientIds.contains(ingredientId),
                    orderIds,
                    recipeVersionIds,
                    List.copyOf(ingredientContributions),
                    List.copyOf(lotEvidence),
                    List.copyOf(lotLoad.excluded().getOrDefault(ingredientId, List.of()))
            ));
        }

        List<PreparationDemand> preparations = preparationDemand(
                orderLines.stream()
                        .filter(line -> !unresolvedLineKeys.contains(line.key()))
                        .toList(),
                ingredientsByRecipe
        );
        return new DemandCalculation(
                scope,
                context.asOfDate(),
                context.horizonEndDate(),
                Instant.now(),
                DemandCalculation.METHOD,
                INCLUDED_ORDER_STATUSES,
                List.copyOf(ingredientResults),
                preparations,
                List.copyOf(unresolvedOrderEvidence)
        );
    }

    @Transactional
    public SafetyStockSetting configureSafetyStock(
            String kitchenId,
            String locationId,
            String ingredientId,
            BigDecimal quantity,
            String unit,
            Integer expectedVersion,
            String actor
    ) {
        requireText(actor, "Actor");
        if (quantity == null || quantity.signum() < 0) {
            throw new DemandValidationException(
                    "Safety-stock quantity must be zero or positive."
            );
        }
        validateKitchenLocation(kitchenId, locationId);
        IngredientRow ingredient = loadIngredient(kitchenId, ingredientId);
        CanonicalQuantity canonical;
        try {
            canonical = units.toIngredientBase(
                    ingredient.asIngredient(),
                    quantity,
                    unit
            );
        } catch (IncompatibleUnitException error) {
            throw new DemandValidationException(error.getMessage(), error);
        }
        BigDecimal storedQuantity;
        try {
            storedQuantity = canonical.quantity().setScale(6, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException error) {
            throw new DemandValidationException(
                    "Safety stock supports at most six canonical decimal places.",
                    error
            );
        }
        Instant updatedAt = Instant.now();

        ExistingSafety existing = jdbc.sql("""
                        SELECT version
                        FROM ingredient_safety_stock
                        WHERE kitchen_id = :kitchen
                          AND location_id = :location
                          AND ingredient_id = :ingredient
                        """)
                .param("kitchen", kitchenId)
                .param("location", locationId)
                .param("ingredient", ingredientId)
                .query((rs, row) -> new ExistingSafety(rs.getInt("version")))
                .optional()
                .orElse(null);

        int newVersion;
        if (existing == null) {
            if (expectedVersion != null && expectedVersion != 0) {
                throw new RecommendationConflictException(
                        "Safety-stock setting does not exist at the expected version."
                );
            }
            try {
                jdbc.sql("""
                                INSERT INTO ingredient_safety_stock
                                (kitchen_id, location_id, ingredient_id, quantity,
                                 unit, version, updated_at, updated_by)
                                VALUES
                                (:kitchen, :location, :ingredient, :quantity,
                                 :unit, 1, :updatedAt, :actor)
                                """)
                        .param("kitchen", kitchenId)
                        .param("location", locationId)
                        .param("ingredient", ingredientId)
                        .param("quantity", storedQuantity)
                        .param("unit", canonical.unit())
                        .param("updatedAt", JdbcTimestamp.utc(updatedAt))
                        .param("actor", actor)
                        .update();
            } catch (DataIntegrityViolationException error) {
                throw new RecommendationConflictException(
                        "Safety stock changed concurrently; reload before retrying."
                );
            }
            newVersion = 1;
        } else {
            if (expectedVersion == null || expectedVersion != existing.version()) {
                throw new RecommendationConflictException(
                        "Safety stock changed; expected version " + existing.version() + "."
                );
            }
            int changed = jdbc.sql("""
                            UPDATE ingredient_safety_stock
                            SET quantity = :quantity,
                                unit = :unit,
                                version = version + 1,
                                updated_at = :updatedAt,
                                updated_by = :actor
                            WHERE kitchen_id = :kitchen
                              AND location_id = :location
                              AND ingredient_id = :ingredient
                              AND version = :version
                            """)
                    .param("quantity", storedQuantity)
                    .param("unit", canonical.unit())
                    .param("updatedAt", JdbcTimestamp.utc(updatedAt))
                    .param("actor", actor)
                    .param("kitchen", kitchenId)
                    .param("location", locationId)
                    .param("ingredient", ingredientId)
                    .param("version", expectedVersion)
                    .update();
            if (changed != 1) {
                throw new RecommendationConflictException(
                        "Safety stock changed concurrently; reload before retrying."
                );
            }
            newVersion = existing.version() + 1;
        }

        return new SafetyStockSetting(
                kitchenId,
                locationId,
                ingredientId,
                clean(storedQuantity),
                canonical.unit(),
                newVersion,
                updatedAt,
                actor
        );
    }

    @Transactional(readOnly = true)
    public List<SafetyStockSetting> safetyStock(
            String kitchenId,
            String locationId
    ) {
        validateKitchenLocation(kitchenId, locationId);
        return jdbc.sql("""
                        SELECT kitchen_id, location_id, ingredient_id, quantity,
                               unit, version, updated_at, updated_by
                        FROM ingredient_safety_stock
                        WHERE kitchen_id = :kitchen
                          AND location_id = :location
                        ORDER BY ingredient_id
                        """)
                .param("kitchen", kitchenId)
                .param("location", locationId)
                .query((rs, row) -> new SafetyStockSetting(
                        rs.getString("kitchen_id"),
                        rs.getString("location_id"),
                        rs.getString("ingredient_id"),
                        clean(rs.getBigDecimal("quantity")),
                        rs.getString("unit"),
                        rs.getInt("version"),
                        instant(rs.getObject("updated_at", OffsetDateTime.class)),
                        rs.getString("updated_by")
                ))
                .list();
    }

    private ScopeContext validateScope(DemandScope scope) {
        Objects.requireNonNull(scope, "Demand scope is required.");
        requireText(scope.kitchenId(), "Kitchen");
        requireText(scope.locationId(), "Location");
        Objects.requireNonNull(scope.horizonStart(), "Horizon start is required.");
        Objects.requireNonNull(scope.horizonEnd(), "Horizon end is required.");
        Objects.requireNonNull(scope.asOf(), "Calculation time is required.");
        Instant validationTime = Instant.now();
        if (scope.asOf().isBefore(validationTime.minus(MAX_AS_OF_AGE))
                || scope.asOf().isAfter(validationTime.plus(MAX_FUTURE_CLOCK_SKEW))) {
            throw new DemandValidationException(
                    "Calculation asOf must represent the current operational state "
                            + "(within five minutes, allowing five seconds of clock skew)."
            );
        }
        if (!scope.horizonStart().isBefore(scope.horizonEnd())) {
            throw new DemandValidationException("Horizon start must be before horizon end.");
        }
        if (scope.asOf().isBefore(scope.horizonStart())
                || !scope.asOf().isBefore(scope.horizonEnd())) {
            throw new DemandValidationException(
                    "Calculation time must fall within the demand horizon."
            );
        }
        if (Duration.between(scope.horizonStart(), scope.horizonEnd())
                .compareTo(MAX_HORIZON) > 0) {
            throw new DemandValidationException("Demand horizon cannot exceed 31 days.");
        }

        String timezone = validateKitchenLocation(
                scope.kitchenId(),
                scope.locationId()
        );
        ZoneId zone;
        try {
            zone = ZoneId.of(timezone);
        } catch (RuntimeException error) {
            throw new DemandValidationException(
                    "Kitchen has an invalid timezone: " + timezone,
                    error
            );
        }
        LocalDate asOfDate = scope.asOf().atZone(zone).toLocalDate();
        LocalDate horizonEndDate = scope.horizonEnd()
                .minusNanos(1)
                .atZone(zone)
                .toLocalDate();
        return new ScopeContext(zone, asOfDate, horizonEndDate);
    }

    private String validateKitchenLocation(String kitchenId, String locationId) {
        requireText(kitchenId, "Kitchen");
        requireText(locationId, "Location");
        return jdbc.sql("""
                        SELECT k.timezone
                        FROM kitchens k
                        JOIN kitchen_locations location
                          ON location.kitchen_id = k.id
                        WHERE k.id = :kitchen
                          AND location.id = :location
                          AND k.active = TRUE
                          AND location.active = TRUE
                        """)
                .param("kitchen", kitchenId)
                .param("location", locationId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new DemandValidationException(
                        "Active kitchen/location scope was not found."
                ));
    }

    private Map<String, IngredientRow> loadIngredients(String kitchenId) {
        Map<String, IngredientRow> result = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT id, name, base_unit, active, reorder_point
                        FROM ingredients
                        WHERE kitchen_id = :kitchen
                        ORDER BY id
                        """)
                .param("kitchen", kitchenId)
                .query((rs, row) -> new IngredientRow(
                        rs.getString("id"),
                        rs.getString("name"),
                        rs.getString("base_unit"),
                        rs.getBoolean("active"),
                        rs.getBigDecimal("reorder_point")
                ))
                .list()
                .forEach(ingredient -> result.put(ingredient.id(), ingredient));
        return result;
    }

    private IngredientRow loadIngredient(String kitchenId, String ingredientId) {
        requireText(ingredientId, "Ingredient");
        return jdbc.sql("""
                        SELECT id, name, base_unit, active, reorder_point
                        FROM ingredients
                        WHERE kitchen_id = :kitchen
                          AND id = :ingredient
                        """)
                .param("kitchen", kitchenId)
                .param("ingredient", ingredientId)
                .query((rs, row) -> new IngredientRow(
                        rs.getString("id"),
                        rs.getString("name"),
                        rs.getString("base_unit"),
                        rs.getBoolean("active"),
                        rs.getBigDecimal("reorder_point")
                ))
                .optional()
                .orElseThrow(() -> new DemandValidationException(
                        "Ingredient was not found in the requested kitchen."
                ));
    }

    private List<OrderLineRow> loadOrderLines(DemandScope scope) {
        return jdbc.sql("""
                        SELECT orders.id AS order_id,
                               orders.created_at AS ordered_at,
                               orders.required_at,
                               item.line_number,
                               item.dish_id,
                               dish.name AS dish_name,
                               dish.kitchen_id AS dish_kitchen_id,
                               item.recipe_version_id,
                               item.recipe_pin_provenance,
                               item.quantity AS ordered_quantity,
                               item.prepared_quantity,
                               recipe.yield_quantity,
                               recipe.yield_unit,
                               recipe.yield_provenance
                        FROM customer_orders orders
                        JOIN order_items item ON item.order_id = orders.id
                        JOIN dishes dish
                          ON dish.id = item.dish_id
                         AND dish.kitchen_id = orders.kitchen_id
                        JOIN recipe_versions recipe
                          ON recipe.id = item.recipe_version_id
                         AND recipe.dish_id = item.dish_id
                         AND recipe.kitchen_id = orders.kitchen_id
                        WHERE orders.kitchen_id = :kitchen
                          AND orders.location_id = :location
                          AND orders.status = 'QUEUED'
                          AND orders.created_at <= :asOf
                          AND orders.required_at < :horizonEnd
                          AND item.prepared_quantity < item.quantity
                        ORDER BY orders.required_at, orders.id, item.line_number
                        """)
                .param("kitchen", scope.kitchenId())
                .param("location", scope.locationId())
                .param("asOf", JdbcTimestamp.utc(scope.asOf()))
                .param("horizonEnd", JdbcTimestamp.utc(scope.horizonEnd()))
                .query((rs, row) -> new OrderLineRow(
                        rs.getString("order_id"),
                        rs.getInt("line_number"),
                        rs.getString("dish_id"),
                        rs.getString("dish_name"),
                        rs.getString("dish_kitchen_id"),
                        rs.getString("recipe_version_id"),
                        rs.getString("recipe_pin_provenance"),
                        rs.getString("yield_provenance"),
                        rs.getInt("ordered_quantity"),
                        rs.getInt("prepared_quantity"),
                        instant(rs.getObject("ordered_at", OffsetDateTime.class)),
                        instant(rs.getObject("required_at", OffsetDateTime.class)),
                        rs.getBigDecimal("yield_quantity"),
                        rs.getString("yield_unit")
                ))
                .list();
    }

    private Map<OrderLineKey, Set<String>> loadPotentiallyAffectedIngredients(
            DemandScope scope
    ) {
        Map<OrderLineKey, Set<String>> result = new HashMap<>();
        jdbc.sql("""
                        SELECT DISTINCT orders.id AS order_id,
                               item.line_number,
                               recipe_item.ingredient_id
                        FROM customer_orders orders
                        JOIN order_items item ON item.order_id = orders.id
                        JOIN recipe_versions pinned
                          ON pinned.id = item.recipe_version_id
                         AND pinned.dish_id = item.dish_id
                         AND pinned.kitchen_id = orders.kitchen_id
                        JOIN recipe_versions candidate
                          ON candidate.kitchen_id = orders.kitchen_id
                         AND (
                           (item.recipe_pin_provenance = 'LEGACY_RECONSTRUCTED'
                            AND candidate.dish_id = item.dish_id)
                           OR (item.recipe_pin_provenance <> 'LEGACY_RECONSTRUCTED'
                               AND candidate.id = item.recipe_version_id)
                         )
                        JOIN recipe_ingredients recipe_item
                          ON recipe_item.recipe_version_id = candidate.id
                         AND recipe_item.kitchen_id = orders.kitchen_id
                        WHERE orders.kitchen_id = :kitchen
                          AND orders.location_id = :location
                          AND orders.status = 'QUEUED'
                          AND orders.created_at <= :asOf
                          AND orders.required_at < :horizonEnd
                          AND item.prepared_quantity < item.quantity
                          AND (
                            item.recipe_pin_provenance = 'LEGACY_RECONSTRUCTED'
                            OR pinned.yield_provenance NOT IN ('OPERATOR_ENTERED', 'OWNER_CONFIRMED')
                          )
                        ORDER BY orders.id, item.line_number,
                                 recipe_item.ingredient_id
                        """)
                .param("kitchen", scope.kitchenId())
                .param("location", scope.locationId())
                .param("asOf", JdbcTimestamp.utc(scope.asOf()))
                .param("horizonEnd", JdbcTimestamp.utc(scope.horizonEnd()))
                .query((rs, row) -> new PotentialIngredientRow(
                        rs.getString("order_id"),
                        rs.getInt("line_number"),
                        rs.getString("ingredient_id")
                ))
                .list()
                .forEach(value -> result.computeIfAbsent(
                        new OrderLineKey(value.orderId(), value.lineNumber()),
                        ignored -> new TreeSet<>()
                ).add(value.ingredientId()));
        return result;
    }

    private List<IngredientContributionRow> loadIngredientContributions(
            DemandScope scope
    ) {
        return jdbc.sql("""
                        SELECT orders.id AS order_id,
                               orders.created_at AS ordered_at,
                               orders.required_at,
                               item.line_number,
                               item.dish_id,
                               dish.kitchen_id AS dish_kitchen_id,
                               item.recipe_version_id,
                               item.recipe_pin_provenance,
                               item.quantity AS ordered_quantity,
                               item.prepared_quantity,
                               recipe.yield_quantity,
                               recipe.yield_unit,
                               recipe.yield_provenance,
                               recipe_item.ingredient_id,
                               ingredient.kitchen_id AS ingredient_kitchen_id,
                               recipe_item.quantity AS recipe_quantity,
                               recipe_item.unit AS recipe_unit
                        FROM customer_orders orders
                        JOIN order_items item ON item.order_id = orders.id
                        JOIN dishes dish
                          ON dish.id = item.dish_id
                         AND dish.kitchen_id = orders.kitchen_id
                        JOIN recipe_versions recipe
                          ON recipe.id = item.recipe_version_id
                         AND recipe.dish_id = item.dish_id
                         AND recipe.kitchen_id = orders.kitchen_id
                        JOIN recipe_ingredients recipe_item
                          ON recipe_item.recipe_version_id = recipe.id
                         AND recipe_item.kitchen_id = orders.kitchen_id
                        JOIN ingredients ingredient
                          ON ingredient.id = recipe_item.ingredient_id
                         AND ingredient.kitchen_id = recipe_item.kitchen_id
                        WHERE orders.kitchen_id = :kitchen
                          AND orders.location_id = :location
                          AND orders.status = 'QUEUED'
                          AND orders.created_at <= :asOf
                          AND orders.required_at < :horizonEnd
                          AND item.prepared_quantity < item.quantity
                        ORDER BY orders.required_at, orders.id, item.line_number,
                                 recipe_item.ingredient_id
                        """)
                .param("kitchen", scope.kitchenId())
                .param("location", scope.locationId())
                .param("asOf", JdbcTimestamp.utc(scope.asOf()))
                .param("horizonEnd", JdbcTimestamp.utc(scope.horizonEnd()))
                .query((rs, row) -> new IngredientContributionRow(
                        rs.getString("order_id"),
                        rs.getInt("line_number"),
                        rs.getString("dish_id"),
                        rs.getString("dish_kitchen_id"),
                        rs.getString("recipe_version_id"),
                        rs.getString("recipe_pin_provenance"),
                        rs.getString("yield_provenance"),
                        rs.getInt("ordered_quantity"),
                        rs.getInt("prepared_quantity"),
                        instant(rs.getObject("ordered_at", OffsetDateTime.class)),
                        instant(rs.getObject("required_at", OffsetDateTime.class)),
                        rs.getBigDecimal("yield_quantity"),
                        rs.getString("yield_unit"),
                        rs.getString("ingredient_id"),
                        rs.getString("ingredient_kitchen_id"),
                        rs.getBigDecimal("recipe_quantity"),
                        rs.getString("recipe_unit")
                ))
                .list();
    }

    private Map<String, SafetyValue> loadSafetyStock(
            DemandScope scope,
            Map<String, IngredientRow> ingredients
    ) {
        Map<String, SafetyValue> result = new HashMap<>();
        jdbc.sql("""
                        SELECT ingredient_id, quantity, unit
                        FROM ingredient_safety_stock
                        WHERE kitchen_id = :kitchen
                          AND location_id = :location
                          AND updated_at <= :asOf
                        """)
                .param("kitchen", scope.kitchenId())
                .param("location", scope.locationId())
                .param("asOf", JdbcTimestamp.utc(scope.asOf()))
                .query((rs, row) -> new SafetyRow(
                        rs.getString("ingredient_id"),
                        rs.getBigDecimal("quantity"),
                        rs.getString("unit")
                ))
                .list()
                .forEach(row -> {
                    IngredientRow ingredient = requireIngredient(
                            ingredients,
                            row.ingredientId()
                    );
                    try {
                        CanonicalQuantity canonical = units.toIngredientBase(
                                ingredient.asIngredient(),
                                row.quantity(),
                                row.unit()
                        );
                        result.put(
                                row.ingredientId(),
                                new SafetyValue(canonical.quantity(), "LOCATION_OVERRIDE")
                        );
                    } catch (IncompatibleUnitException error) {
                        throw new DemandValidationException(
                                "Safety stock for " + row.ingredientId()
                                        + " has an incompatible unit.",
                                error
                        );
                    }
                });

        return result;
    }

    private LotLoadResult loadLots(
            DemandScope scope,
            ScopeContext context,
            Map<String, IngredientRow> ingredients
    ) {
        Map<String, List<ProjectedLot>> eligible = new HashMap<>();
        Map<String, List<ExcludedLotEvidence>> excluded = new HashMap<>();
        List<LotRow> rows = jdbc.sql("""
                        SELECT id, ingredient_id, quantity_remaining, unit,
                               purchased_at, expires_at, expiry_provenance, status
                        FROM stock_lots
                        WHERE kitchen_id = :kitchen
                          AND location_id = :location
                          AND quantity_remaining > 0
                          AND created_at <= :asOf
                          AND purchased_at <= :asOfDate
                        ORDER BY expires_at, purchased_at, id
                        """)
                .param("kitchen", scope.kitchenId())
                .param("location", scope.locationId())
                .param("asOf", JdbcTimestamp.utc(scope.asOf()))
                .param("asOfDate", context.asOfDate())
                .query((rs, row) -> new LotRow(
                        rs.getString("id"),
                        rs.getString("ingredient_id"),
                        rs.getBigDecimal("quantity_remaining"),
                        rs.getString("unit"),
                        rs.getObject("purchased_at", LocalDate.class),
                        rs.getObject("expires_at", LocalDate.class),
                        rs.getString("expiry_provenance"),
                        rs.getString("status")
                ))
                .list();

        for (LotRow row : rows) {
            IngredientRow ingredient = requireIngredient(ingredients, row.ingredientId());
            String exclusion = null;
            if (!"AVAILABLE".equals(row.status())) {
                exclusion = "STATUS_" + row.status();
            } else if (row.expiresAt() == null) {
                exclusion = "UNRESOLVED_EXPIRY";
            } else if (row.expiresAt().isBefore(context.asOfDate())) {
                exclusion = "EXPIRED_AS_OF_KITCHEN_DATE";
            }

            CanonicalQuantity canonical = null;
            if (exclusion == null) {
                try {
                    canonical = units.toIngredientBase(
                            ingredient.asIngredient(),
                            row.quantity(),
                            row.unit()
                    );
                } catch (IncompatibleUnitException error) {
                    exclusion = "INCOMPATIBLE_UNIT";
                }
            }

            if (exclusion != null) {
                excluded.computeIfAbsent(row.ingredientId(), ignored -> new ArrayList<>())
                        .add(new ExcludedLotEvidence(
                                row.id(),
                                clean(row.quantity()),
                                row.unit(),
                                row.expiresAt(),
                                row.status(),
                                exclusion
                        ));
                continue;
            }

            eligible.computeIfAbsent(row.ingredientId(), ignored -> new ArrayList<>())
                    .add(new ProjectedLot(
                            row.id(),
                            row.quantity(),
                            row.unit(),
                            canonical.quantity(),
                            row.purchasedAt(),
                            row.expiresAt(),
                            row.expiryProvenance()
                    ));
        }
        Comparator<ProjectedLot> fefo = Comparator
                .comparing(ProjectedLot::expiresAt)
                .thenComparing(ProjectedLot::purchasedAt)
                .thenComparing(ProjectedLot::id);
        eligible.values().forEach(lots -> lots.sort(fefo));
        return new LotLoadResult(eligible, excluded);
    }

    static BigDecimal allocateProjectedDemand(
            List<ProjectedLot> lots,
            List<OrderDemandEvidence> contributions,
            ZoneId zone,
            LocalDate asOfDate
    ) {
        BigDecimal shortfall = BigDecimal.ZERO;
        for (OrderDemandEvidence contribution : contributions) {
            BigDecimal remainingDemand = contribution.canonicalDemand();
            LocalDate demandDate = contribution.requiredAt().atZone(zone).toLocalDate();
            if (demandDate.isBefore(asOfDate)) {
                demandDate = asOfDate;
            }
            for (ProjectedLot lot : lots) {
                if (remainingDemand.signum() == 0) {
                    break;
                }
                if (lot.remaining().signum() == 0
                        || lot.expiresAt().isBefore(demandDate)) {
                    continue;
                }
                BigDecimal used = remainingDemand.min(lot.remaining());
                lot.consume(used);
                remainingDemand = remainingDemand.subtract(used);
            }
            shortfall = shortfall.add(remainingDemand);
        }
        return shortfall;
    }

    private List<PreparationDemand> preparationDemand(
            List<OrderLineRow> lines,
            Map<String, Set<String>> ingredientsByRecipe
    ) {
        Map<PreparationKey, PreparationAccumulator> values = new LinkedHashMap<>();
        for (OrderLineRow line : lines) {
            PreparationKey key = new PreparationKey(
                    line.dishId(),
                    line.dishName(),
                    line.recipeVersionId()
            );
            PreparationAccumulator value = values.computeIfAbsent(
                    key,
                    ignored -> new PreparationAccumulator()
            );
            value.quantity = value.quantity.add(
                    BigDecimal.valueOf(line.remainingQuantity())
            );
            value.orderIds.add(line.orderId());
        }
        return values.entrySet().stream()
                .map(entry -> new PreparationDemand(
                        entry.getKey().dishId(),
                        entry.getKey().dishName(),
                        entry.getKey().recipeVersionId(),
                        clean(entry.getValue().quantity),
                        "each",
                        List.copyOf(entry.getValue().orderIds),
                        List.copyOf(ingredientsByRecipe.getOrDefault(
                                entry.getKey().recipeVersionId(),
                                Set.of()
                        ))
                ))
                .toList();
    }

    private static IngredientRow requireIngredient(
            Map<String, IngredientRow> ingredients,
            String ingredientId
    ) {
        IngredientRow result = ingredients.get(ingredientId);
        if (result == null) {
            throw new DemandValidationException(
                    "Ingredient " + ingredientId + " is outside the requested kitchen."
            );
        }
        return result;
    }

    private static boolean trustedRecipePin(String provenance) {
        return "CAPTURED_AT_ORDER".equals(provenance)
                || "LEGACY_UNAMBIGUOUS".equals(provenance)
                || "OWNER_CONFIRMED".equals(provenance);
    }

    private static boolean trustedRecipeYield(String provenance) {
        return "OPERATOR_ENTERED".equals(provenance)
                || "OWNER_CONFIRMED".equals(provenance);
    }

    private static String unresolvedResolutionCode(OrderLineRow line) {
        boolean pinTrusted = trustedRecipePin(line.recipePinProvenance());
        boolean yieldTrusted = trustedRecipeYield(line.recipeYieldProvenance());
        if (!pinTrusted && !yieldTrusted) {
            return "OWNER_RECIPE_PIN_AND_YIELD_REVIEW_REQUIRED";
        }
        if (!pinTrusted) {
            return "OWNER_RECIPE_PIN_REVIEW_REQUIRED";
        }
        if (!yieldTrusted) {
            return "OWNER_RECIPE_YIELD_REVIEW_REQUIRED";
        }
        throw new IllegalArgumentException("Resolved order line has no review code.");
    }

    private static BigDecimal clean(BigDecimal value) {
        if (value == null || value.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return value.stripTrailingZeros();
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new DemandValidationException(label + " is required.");
        }
    }

    private record ScopeContext(
            ZoneId zone,
            LocalDate asOfDate,
            LocalDate horizonEndDate
    ) {
    }

    private record IngredientRow(
            String id,
            String name,
            String baseUnit,
            boolean active,
            BigDecimal reorderPoint
    ) {
        Ingredient asIngredient() {
            return new Ingredient(id, name, baseUnit, active);
        }

        String canonicalUnit(UnitConversionService units) {
            return units.canonicalUnit(baseUnit);
        }
    }

    private record OrderLineKey(String orderId, int lineNumber) {
    }

    private record PotentialIngredientRow(
            String orderId,
            int lineNumber,
            String ingredientId
    ) {
    }

    private record OrderLineRow(
            String orderId,
            int lineNumber,
            String dishId,
            String dishName,
            String dishKitchenId,
            String recipeVersionId,
            String recipePinProvenance,
            String recipeYieldProvenance,
            int quantity,
            int preparedQuantity,
            Instant orderedAt,
            Instant requiredAt,
            BigDecimal yieldQuantity,
            String yieldUnit
    ) {
        OrderLineKey key() {
            return new OrderLineKey(orderId, lineNumber);
        }

        int remainingQuantity() {
            return quantity - preparedQuantity;
        }
    }

    private record IngredientContributionRow(
            String orderId,
            int lineNumber,
            String dishId,
            String dishKitchenId,
            String recipeVersionId,
            String recipePinProvenance,
            String recipeYieldProvenance,
            int orderedQuantity,
            int preparedQuantity,
            Instant orderedAt,
            Instant requiredAt,
            BigDecimal yieldQuantity,
            String yieldUnit,
            String ingredientId,
            String ingredientKitchenId,
            BigDecimal recipeIngredientQuantity,
            String recipeIngredientUnit
    ) {
        OrderLineKey lineKey() {
            return new OrderLineKey(orderId, lineNumber);
        }

        int remainingQuantity() {
            return orderedQuantity - preparedQuantity;
        }
    }

    private record SafetyRow(
            String ingredientId,
            BigDecimal quantity,
            String unit
    ) {
    }

    private record ExistingSafety(int version) {
    }

    private record SafetyValue(BigDecimal quantity, String source) {
        static SafetyValue zero() {
            return new SafetyValue(BigDecimal.ZERO, "ZERO_DEFAULT");
        }
    }

    private record LotRow(
            String id,
            String ingredientId,
            BigDecimal quantity,
            String unit,
            LocalDate purchasedAt,
            LocalDate expiresAt,
            String expiryProvenance,
            String status
    ) {
    }

    static final class ProjectedLot {
        private final String id;
        private final BigDecimal storedQuantity;
        private final String storedUnit;
        private final BigDecimal originalQuantity;
        private final LocalDate purchasedAt;
        private final LocalDate expiresAt;
        private final String expiryProvenance;
        private BigDecimal remaining;

        ProjectedLot(
                String id,
                BigDecimal storedQuantity,
                String storedUnit,
                BigDecimal originalQuantity,
                LocalDate purchasedAt,
                LocalDate expiresAt,
                String expiryProvenance
        ) {
            this.id = id;
            this.storedQuantity = storedQuantity;
            this.storedUnit = storedUnit;
            this.originalQuantity = originalQuantity;
            this.purchasedAt = purchasedAt;
            this.expiresAt = expiresAt;
            this.expiryProvenance = expiryProvenance;
            this.remaining = originalQuantity;
        }

        ProjectedLot copy() {
            return new ProjectedLot(
                    id,
                    storedQuantity,
                    storedUnit,
                    originalQuantity,
                    purchasedAt,
                    expiresAt,
                    expiryProvenance
            );
        }

        void consume(BigDecimal quantity) {
            remaining = remaining.subtract(quantity);
        }

        String id() {
            return id;
        }

        BigDecimal storedQuantity() {
            return storedQuantity;
        }

        String storedUnit() {
            return storedUnit;
        }

        BigDecimal originalQuantity() {
            return originalQuantity;
        }

        LocalDate purchasedAt() {
            return purchasedAt;
        }

        LocalDate expiresAt() {
            return expiresAt;
        }

        String expiryProvenance() {
            return expiryProvenance;
        }

        BigDecimal remaining() {
            return remaining;
        }
    }

    private record LotLoadResult(
            Map<String, List<ProjectedLot>> eligible,
            Map<String, List<ExcludedLotEvidence>> excluded
    ) {
    }

    private record PreparationKey(
            String dishId,
            String dishName,
            String recipeVersionId
    ) {
    }

    private static final class PreparationAccumulator {
        private BigDecimal quantity = BigDecimal.ZERO;
        private final Set<String> orderIds = new LinkedHashSet<>();
    }
}
