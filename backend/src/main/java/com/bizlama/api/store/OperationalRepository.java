package com.bizlama.api.store;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.bizlama.api.config.JdbcTimestamp;
import com.bizlama.api.config.WorkspaceProperties;
import com.bizlama.api.domain.ActivityEvent;
import com.bizlama.api.domain.Dish;
import com.bizlama.api.domain.Feedback;
import com.bizlama.api.domain.Ingredient;
import com.bizlama.api.domain.InventoryLot;
import com.bizlama.api.domain.InventorySummary;
import com.bizlama.api.domain.MenuCategory;
import com.bizlama.api.domain.Order;
import com.bizlama.api.domain.OrderListItem;
import com.bizlama.api.domain.OrderSummary;
import com.bizlama.api.domain.ReceiptImport;
import com.bizlama.api.domain.RecipeVersion;
import com.bizlama.api.domain.StockLot;
import com.bizlama.api.domain.StockMovement;
import com.bizlama.api.experiment.ExperimentResponse;
import com.bizlama.api.experiment.ExperimentStatus;
import com.bizlama.api.outbox.OutboxEvent;
import com.bizlama.api.outbox.OutboxEventDraft;
import com.bizlama.api.outbox.TransactionalOutboxService;
import com.bizlama.api.orders.OrderTransitionService;
import com.bizlama.api.quantity.CanonicalQuantity;
import com.bizlama.api.quantity.IncompatibleUnitException;
import com.bizlama.api.quantity.UnitConversionService;

@Repository
public class OperationalRepository {

    private final JdbcClient jdbc;
    private final TransactionalOutboxService outbox;
    private final WorkspaceProperties workspace;
    private final UnitConversionService units;
    private final OrderTransitionService orderTransitions;

    public OperationalRepository(
            JdbcClient jdbc,
            TransactionalOutboxService outbox,
            WorkspaceProperties workspace,
            UnitConversionService units,
            OrderTransitionService orderTransitions) {

        this.jdbc = jdbc;
        this.outbox = outbox;
        this.workspace = workspace;
        this.units = units;
        this.orderTransitions = orderTransitions;
    }

    // ============================================================
    // INGREDIENTS
    // ============================================================

    public List<Ingredient> ingredients() {
        return scopedSql("""
                        SELECT id, name, base_unit, active
                        FROM ingredients
                        WHERE kitchen_id = :workspaceKitchen
                        ORDER BY name
                        """)
                .query((rs, row) -> ingredient(rs,row))
                .list();
    }

    public Optional<Ingredient> ingredient(String id) {
        return scopedSql("""
                        SELECT id, name, base_unit, active
                        FROM ingredients
                        WHERE id = :id
                          AND kitchen_id = :workspaceKitchen
                        """)
                .param("id", id)
                .query((rs, row) -> ingredient(rs,row))
                .optional();
    }

    @Transactional
    public Ingredient saveIngredient(Ingredient value) {
        String canonicalUnit = units.canonicalUnit(value.baseUnit());
        Ingredient existing = ingredient(value.id()).orElse(null);
        if (existing != null
                && !units.canonicalUnit(existing.baseUnit()).equals(canonicalUnit)
                && hasHistoricalQuantities(value.id())) {
            throw new IncompatibleUnitException(
                    "Ingredient base dimension cannot change after recipes, inventory, "
                            + "receipts, safety stock, or recommendations reference it."
            );
        }

        Ingredient stored = new Ingredient(
                value.id(),
                value.name(),
                canonicalUnit,
                value.active()
        );
        int updated = scopedSql("""
                        UPDATE ingredients
                        SET name = :name,
                            base_unit = :unit,
                            active = :active
                        WHERE id = :id
                          AND kitchen_id = :workspaceKitchen
                        """)
                .params(Map.of(
                        "id", stored.id(),
                        "name", stored.name(),
                        "unit", stored.baseUnit(),
                        "active", stored.active()))
                .update();

        if (updated == 0) {
            scopedSql("""
                            INSERT INTO ingredients
                            (id, name, base_unit, active, kitchen_id)
                            VALUES (:id, :name, :unit, :active, :workspaceKitchen)
                            """)
                    .params(Map.of(
                            "id", stored.id(),
                            "name", stored.name(),
                            "unit", stored.baseUnit(),
                            "active", stored.active()))
                    .update();
        }

        return stored;
    }

    private boolean hasHistoricalQuantities(String ingredientId) {
        Long references = jdbc.sql("""
                        SELECT
                          (SELECT COUNT(*) FROM recipe_ingredients
                           WHERE ingredient_id = :ingredient
                             AND kitchen_id = :workspaceKitchen)
                          + (SELECT COUNT(*) FROM stock_lots
                             WHERE ingredient_id = :ingredient
                               AND kitchen_id = :workspaceKitchen)
                          + (SELECT COUNT(*) FROM stock_movements
                             WHERE ingredient_id = :ingredient
                               AND kitchen_id = :workspaceKitchen)
                          + (SELECT COUNT(*) FROM receipt_items
                             WHERE ingredient_id = :ingredient
                               AND EXISTS (
                                 SELECT 1
                                 FROM receipt_imports receipt
                                 WHERE receipt.id = receipt_items.receipt_id
                                   AND receipt.kitchen_id = :workspaceKitchen))
                          + (SELECT COUNT(*) FROM ingredient_safety_stock
                             WHERE ingredient_id = :ingredient
                               AND kitchen_id = :workspaceKitchen)
                          + (SELECT COUNT(*) FROM recommendations
                             WHERE ingredient_id = :ingredient
                               AND kitchen_id = :workspaceKitchen)
                        """)
                .param("ingredient", ingredientId)
                .param("workspaceKitchen", workspace.kitchenId())
                .query(Long.class)
                .single();
        return references != null && references > 0;
    }

    public void deleteIngredient(String id) {
        scopedSql("""
                        UPDATE ingredients
                        SET active = FALSE
                        WHERE id = :id
                          AND kitchen_id = :workspaceKitchen
                        """)
                .param("id", id)
                .update();
    }

    // ============================================================
    // MENU CATEGORIES
    // ============================================================

    public List<MenuCategory> menuCategories() {
        return scopedSql("""
                        SELECT id, name
                        FROM catalog_categories
                        WHERE kitchen_id = :workspaceKitchen
                        ORDER BY name
                        """)
                .query((rs, rowNum) ->
                        new MenuCategory(
                                rs.getString("id"),
                                rs.getString("name")))
                .list();
    }

    public Optional<MenuCategory> menuCategory(String id) {
        return scopedSql("""
                        SELECT id, name
                        FROM catalog_categories
                        WHERE id = :id
                          AND kitchen_id = :workspaceKitchen
                        """)
                .param("id", id)
                .query((rs, rowNum) ->
                        new MenuCategory(
                                rs.getString("id"),
                                rs.getString("name")))
                .optional();
    }

    // ============================================================
    // DISHES
    // ============================================================

    public List<Dish> dishes() {
        return scopedSql("""
                        SELECT d.id,
                               d.name,
                               d.price,
                               d.active_recipe_version_id,
                               d.active,
                               d.category_id,
                               COALESCE(c.name, 'Other') AS category_name
                        FROM dishes d
                        LEFT JOIN catalog_categories c
                            ON c.id = d.category_id
                           AND c.kitchen_id = d.kitchen_id
                        WHERE d.active = TRUE
                          AND d.kitchen_id = :workspaceKitchen
                        ORDER BY COALESCE(c.name, 'Other'), d.name
                        """)
                .query((rs, row) -> dish(rs, row))
                .list();
    }

    public Optional<Dish> dish(String id) {
        return scopedSql("""
                        SELECT d.id,
                               d.name,
                               d.price,
                               d.active_recipe_version_id,
                               d.active,
                               d.category_id,
                               COALESCE(c.name, 'Other') AS category_name
                        FROM dishes d
                        LEFT JOIN catalog_categories c
                            ON c.id = d.category_id
                           AND c.kitchen_id = d.kitchen_id
                        WHERE d.id = :id
                          AND d.kitchen_id = :workspaceKitchen
                        """)
                .param("id", id)
                .query((rs, row) -> dish(rs, row))
                .optional();
    }

    public Dish saveDish(Dish value) {

        requireWorkspaceCategory(value.categoryId());

        int updated = scopedSql("""
                        UPDATE dishes
                        SET name = :name,
                            price = :price,
                            active = :active,
                            category_id = :category
                        WHERE id = :id
                          AND kitchen_id = :workspaceKitchen
                        """)
                .param("id", value.id())
                .param("name", value.name())
                .param("price", value.price())
                .param("active", value.active())
                .param("category", value.categoryId())
                .update();

        if (updated == 0) {
            scopedSql("""
                            INSERT INTO dishes
                            (id, name, price, active_recipe_version_id, active,
                             category_id, kitchen_id)
                            VALUES (:id, :name, :price, :recipe, :active,
                                    :category, :workspaceKitchen)
                            """)
                    .param("id", value.id())
                    .param("name", value.name())
                    .param("price", value.price())
                    .param("recipe", value.activeRecipeVersionId())
                    .param("active", value.active())
                    .param("category", value.categoryId())
                    .update();
        }

        return dish(value.id()).orElse(value);
    }

    public void deleteDish(String id) {
        scopedSql("""
                        UPDATE dishes
                        SET active = FALSE
                        WHERE id = :id
                          AND kitchen_id = :workspaceKitchen
                        """)
                .param("id", id)
                .update();
    }

    // ============================================================
    // RECIPES
    // ============================================================

    public List<RecipeVersion> recipes() {
        return scopedSql("""
                        SELECT recipe.id
                        FROM recipe_versions recipe
                        WHERE recipe.kitchen_id = :workspaceKitchen
                        ORDER BY recipe.dish_id, recipe.version_number DESC
                        """)
                .query(String.class)
                .list()
                .stream()
                .map(this::recipe)
                .flatMap(Optional::stream)
                .toList();
    }

    public Optional<RecipeVersion> recipe(String id) {
        return scopedSql("""
                        SELECT id,
                               dish_id,
                               version_number,
                               yield_quantity,
                               yield_unit,
                               yield_provenance,
                               change_reason,
                               created_at,
                               created_by,
                               active,
                               approved_by,
                               approved_at,
                               effective_at,
                               superseded_at,
                               superseded_by_version_id
                        FROM recipe_versions
                        WHERE id = :id
                          AND kitchen_id = :workspaceKitchen
                        """)
                .param("id", id)
                .query((rs, rowNum) ->
                        new RecipeVersion(
                                rs.getString("id"),
                                rs.getString("dish_id"),
                                rs.getInt("version_number"),
                                recipeIngredients(rs.getString("id")),
                                recipeSteps(rs.getString("id")),
                                rs.getBigDecimal("yield_quantity"),
                                rs.getString("yield_unit"),
                                rs.getString("yield_provenance"),
                                rs.getString("change_reason"),
                                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                                rs.getString("created_by"),
                                rs.getBoolean("active"),
                                rs.getString("approved_by"),
                                instant(rs, "approved_at"),
                                instant(rs, "effective_at"),
                                instant(rs, "superseded_at"),
                                rs.getString("superseded_by_version_id")))
                .optional();
    }

    private List<RecipeVersion.RecipeIngredient> recipeIngredients(String id) {
        return scopedSql("""
                        SELECT ingredient_id, quantity, unit
                        FROM recipe_ingredients
                        WHERE recipe_version_id = :id
                          AND kitchen_id = :workspaceKitchen
                        ORDER BY ingredient_id
                        """)
                .param("id", id)
                .query((rs, rowNum) ->
                        new RecipeVersion.RecipeIngredient(
                                rs.getString("ingredient_id"),
                                rs.getBigDecimal("quantity"),
                                rs.getString("unit")))
                .list();
    }

    private List<String> recipeSteps(String id) {
        return scopedSql("""
                        SELECT instruction
                        FROM recipe_steps
                        WHERE recipe_version_id = :id
                          AND kitchen_id = :workspaceKitchen
                        ORDER BY step_number
                        """)
                .param("id", id)
                .query(String.class)
                .list();
    }

    public int nextRecipeVersion(String dishId) {

        Integer current = scopedSql("""
                        SELECT MAX(version_number)
                        FROM recipe_versions
                        WHERE dish_id = :dish
                          AND kitchen_id = :workspaceKitchen
                        """)
                .param("dish", dishId)
                .query(Integer.class)
                .optional()
                .orElse(null);

        return current == null ? 1 : current + 1;
    }

    @Transactional
    public RecipeVersion saveRecipeProposal(RecipeVersion recipe) {

        requireWorkspaceDish(recipe.dishId());
        recipe.ingredients().forEach(item ->
                requireWorkspaceIngredient(item.ingredientId()));

        scopedSql("""
                        INSERT INTO recipe_versions
                        (id, dish_id, version_number, yield_quantity, yield_unit,
                         change_reason, active, created_at, created_by, kitchen_id)
                        VALUES
                        (:id, :dish, :version, :yieldQuantity, :yieldUnit,
                         :reason, FALSE, :created, :createdBy, :workspaceKitchen)
                        """)
                .param("id", recipe.id())
                .param("dish", recipe.dishId())
                .param("version", recipe.versionNumber())
                .param("yieldQuantity", recipe.yieldQuantity())
                .param("yieldUnit", recipe.yieldUnit())
                .param("reason", recipe.changeReason())
                .param("created", JdbcTimestamp.utc(recipe.createdAt()))
                .param("createdBy", recipe.createdBy())
                .update();

        for (RecipeVersion.RecipeIngredient item : recipe.ingredients()) {
            scopedSql("""
                            INSERT INTO recipe_ingredients
                            (recipe_version_id, ingredient_id, quantity, unit,
                             kitchen_id)
                            VALUES
                            (:recipe, :ingredient, :quantity, :unit,
                             :workspaceKitchen)
                            """)
                    .param("recipe", recipe.id())
                    .param("ingredient", item.ingredientId())
                    .param("quantity", item.quantity())
                    .param("unit", item.unit())
                    .update();
        }

        for (int index = 0; index < recipe.instructions().size(); index++) {
            scopedSql("""
                            INSERT INTO recipe_steps
                            (recipe_version_id, step_number, instruction,
                             kitchen_id)
                            VALUES
                            (:recipe, :step, :instruction, :workspaceKitchen)
                            """)
                    .param("recipe", recipe.id())
                    .param("step", index + 1)
                    .param("instruction", recipe.instructions().get(index))
                    .update();
        }

        outbox.append(OutboxEventDraft.v1(
                "RECIPE_VERSION_PROPOSED",
                workspace.kitchenId(),
                "recipe_version",
                recipe.id(),
                "recipe-proposed:" + recipe.id(),
                recipe
        ));
        addActivity(
                "Recipe proposal",
                "Recipe version " + recipe.versionNumber()
                        + " is waiting for owner approval");

        return recipe;
    }

    @Transactional
    public RecipeVersion createDishWithRecipe(
            Dish dish,
            int preparationMinutes,
            RecipeVersion recipe) {

        requireWorkspaceCategory(dish.categoryId());
        recipe.ingredients().forEach(item ->
                requireWorkspaceIngredient(item.ingredientId()));

        scopedSql("""
                        INSERT INTO dishes
                        (id, name, price, active_recipe_version_id,
                         active, kitchen_id, category_id, preparation_minutes)
                        VALUES
                        (:id, :name, :price, NULL,
                         TRUE, :workspaceKitchen, :category, :minutes)
                        """)
                .param("id", dish.id())
                .param("name", dish.name())
                .param("price", dish.price())
                .param("category", dish.categoryId())
                .param("minutes", preparationMinutes)
                .update();

        scopedSql("""
                        INSERT INTO recipe_versions
                        (id, dish_id, version_number, yield_quantity, yield_unit,
                         change_reason, active, active_dish_guard, created_at, created_by,
                         approved_by, approved_at, effective_at, kitchen_id)
                        VALUES
                        (:id, :dish, 1, :yieldQuantity, :yieldUnit,
                         :reason, TRUE, :dish, :created, :createdBy,
                         :approvedBy, :approvedAt, :effectiveAt,
                         :workspaceKitchen)
                        """)
                .param("id", recipe.id())
                .param("dish", dish.id())
                .param("yieldQuantity", recipe.yieldQuantity())
                .param("yieldUnit", recipe.yieldUnit())
                .param("reason", recipe.changeReason())
                .param("created", JdbcTimestamp.utc(recipe.createdAt()))
                .param("createdBy", recipe.createdBy())
                .param("approvedBy", recipe.approvedBy())
                .param("approvedAt", JdbcTimestamp.utc(recipe.approvedAt()))
                .param("effectiveAt", JdbcTimestamp.utc(recipe.effectiveAt()))
                .update();

        for (RecipeVersion.RecipeIngredient item : recipe.ingredients()) {
            scopedSql("""
                            INSERT INTO recipe_ingredients
                            (recipe_version_id, ingredient_id, quantity, unit,
                             kitchen_id)
                            VALUES
                            (:recipe, :ingredient, :quantity, :unit,
                             :workspaceKitchen)
                            """)
                    .param("recipe", recipe.id())
                    .param("ingredient", item.ingredientId())
                    .param("quantity", item.quantity())
                    .param("unit", item.unit())
                    .update();
        }

        for (int index = 0; index < recipe.instructions().size(); index++) {
            scopedSql("""
                            INSERT INTO recipe_steps
                            (recipe_version_id, step_number, instruction,
                             kitchen_id)
                            VALUES
                            (:recipe, :step, :instruction, :workspaceKitchen)
                            """)
                    .param("recipe", recipe.id())
                    .param("step", index + 1)
                    .param("instruction", recipe.instructions().get(index))
                    .update();
        }

        scopedSql("""
                        UPDATE dishes
                        SET active_recipe_version_id = :recipe
                        WHERE id = :dish
                          AND kitchen_id = :workspaceKitchen
                        """)
                .param("recipe", recipe.id())
                .param("dish", dish.id())
                .update();

        outbox.append(OutboxEventDraft.v1(
                "RECIPE_VERSION_ACTIVATED",
                workspace.kitchenId(),
                "recipe_version",
                recipe.id(),
                "recipe-activated:" + recipe.id(),
                recipe
        ));
        addActivity(
                "Menu",
                "Created " + dish.name() + " with recipe version 1");

        return recipe(recipe.id()).orElseThrow();
    }

    @Transactional
    public RecipeVersion activateRecipe(
            String recipeId,
            String actor
    ) {
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("Approver is required.");
        }
        RecipeVersion recipe = recipe(recipeId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Recipe version not found"));
        scopedSql("""
                        SELECT id
                        FROM dishes
                        WHERE id = :dish
                          AND kitchen_id = :workspaceKitchen
                        FOR UPDATE
                        """)
                .param("dish", recipe.dishId())
                .query(String.class)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Recipe dish was not found in this kitchen."
                ));
        recipe = recipe(recipeId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Recipe version not found after acquiring its dish lock."
                ));
        if (recipe.active()) {
            return recipe;
        }

        Instant now = Instant.now();
        scopedSql("""
                        UPDATE recipe_versions
                        SET active = FALSE,
                            active_dish_guard = NULL,
                            superseded_at = :now,
                            superseded_by_version_id = :replacement
                        WHERE dish_id = :dish
                          AND kitchen_id = :workspaceKitchen
                          AND active = TRUE
                          AND id <> :replacement
                        """)
                .param("now", JdbcTimestamp.utc(now))
                .param("replacement", recipeId)
                .param("dish", recipe.dishId())
                .update();

        int activated = scopedSql("""
                        UPDATE recipe_versions
                        SET active = TRUE,
                            active_dish_guard = :dish,
                            approved_by = :actor,
                            approved_at = :now,
                            effective_at = :now,
                            superseded_at = NULL,
                            superseded_by_version_id = NULL
                        WHERE id = :id
                          AND dish_id = :dish
                          AND kitchen_id = :workspaceKitchen
                        """)
                .param("actor", actor)
                .param("now", JdbcTimestamp.utc(now))
                .param("id", recipeId)
                .param("dish", recipe.dishId())
                .update();
        if (activated != 1) {
            throw new IllegalStateException("Recipe activation failed.");
        }

        scopedSql("""
                        UPDATE dishes
                        SET active_recipe_version_id = :recipe
                        WHERE id = :dish
                          AND kitchen_id = :workspaceKitchen
                        """)
                .param("recipe", recipeId)
                .param("dish", recipe.dishId())
                .update();

        RecipeVersion activatedRecipe = recipe(recipeId).orElseThrow();
        outbox.append(new OutboxEventDraft(
                null,
                "RECIPE_VERSION_ACTIVATED",
                1,
                workspace.kitchenId(),
                "recipe_version",
                recipeId,
                now,
                recipeId,
                null,
                "recipe-activated:" + recipeId + ":" + now,
                activatedRecipe,
                Map.of("actor", actor)
        ));
        addActivity(
                "Recipe approval",
                "Owner activated recipe version "
                        + recipe.versionNumber());

        return activatedRecipe;
    }

    // ============================================================
    // STOCK
    // ============================================================

    public List<StockLot> stockLots() {
        return scopedSql("""
                        SELECT id,
                               ingredient_id,
                               quantity_remaining,
                               unit,
                               purchased_at,
                               expires_at,
                               source
                        FROM stock_lots
                        WHERE kitchen_id = :workspaceKitchen
                          AND location_id = :workspaceLocation
                          AND status = 'AVAILABLE'
                          AND quantity_remaining > 0
                          AND expires_at >= CURRENT_DATE
                        ORDER BY expires_at
                        """)
                .query(this::stockLot)
                .list();
    }

    public Optional<StockLot> firstAvailableLot(String ingredientId) {
        return scopedSql("""
                        SELECT id,
                               ingredient_id,
                               quantity_remaining,
                               unit,
                               purchased_at,
                               expires_at,
                               source
                        FROM stock_lots
                        WHERE kitchen_id = :workspaceKitchen
                          AND location_id = :workspaceLocation
                          AND ingredient_id = :ingredient
                          AND status = 'AVAILABLE'
                          AND quantity_remaining > 0
                          AND expires_at >= CURRENT_DATE
                        ORDER BY expires_at
                        LIMIT 1
                        """)
                .param("ingredient", ingredientId)
                .query(this::stockLot)
                .optional();
    }

    public List<StockMovement> stockMovements() {

        return scopedSql("""
                        SELECT id,
                               stock_lot_id,
                               ingredient_id,
                               movement_type,
                               quantity_change,
                               unit,
                               reference_type,
                               reference_id,
                               occurred_at
                        FROM stock_movements
                        WHERE kitchen_id = :workspaceKitchen
                          AND location_id = :workspaceLocation
                        ORDER BY occurred_at DESC
                        """)
                .query((rs, rowNum) ->
                        new StockMovement(
                                rs.getString(1),
                                rs.getString(2),
                                rs.getString(3),
                                StockMovement.MovementType.valueOf(
                                        rs.getString(4)),
                                rs.getBigDecimal(5),
                                rs.getString(6),
                                rs.getString(7),
                                rs.getString(8),
                                rs.getObject(9, OffsetDateTime.class)
                                        .toInstant()))
                .list();
    }

    // ============================================================
    // INVENTORY
    // ============================================================

    public List<InventoryLot> searchStockLots(
            String query,
            String status,
            int page,
            int size) {

        String normalizedQuery =
                query == null ? "" : query.trim().toLowerCase();

        String normalizedStatus =
                status == null ? "all" : status.trim().toLowerCase();

        LocalDate today = LocalDate.now();
        LocalDate soon = today.plusDays(7);

        return scopedSql("""
                        SELECT l.id,
                               l.ingredient_id,
                               i.name,
                               l.quantity_remaining,
                               l.unit,
                               l.purchased_at,
                               l.expires_at,
                               l.source,
                               CASE
                                   WHEN l.status = 'QUARANTINED'
                                     OR l.expires_at IS NULL
                                       THEN 'quarantined'
                                   WHEN l.expires_at < :today
                                       THEN 'expired'
                                   WHEN l.expires_at <= :soon
                                       THEN 'expiring'
                                   ELSE 'available'
                               END AS lot_status
                        FROM stock_lots l
                        JOIN ingredients i
                            ON i.id = l.ingredient_id
                           AND i.kitchen_id = l.kitchen_id
                        WHERE l.kitchen_id = :workspaceKitchen
                          AND l.location_id = :workspaceLocation
                          AND l.quantity_remaining > 0
                          AND (
                              :query = ''
                              OR LOWER(i.name) LIKE :pattern
                              OR LOWER(l.id) LIKE :pattern
                          )
                          AND (
                              :status = 'all'
                              OR (
                                  :status = 'quarantined'
                                  AND (
                                      l.status = 'QUARANTINED'
                                      OR l.expires_at IS NULL
                                  )
                              )
                              OR (
                                  :status = 'expired'
                                  AND l.status = 'AVAILABLE'
                                  AND l.expires_at < :today
                              )
                              OR (
                                  :status = 'expiring'
                                  AND l.status = 'AVAILABLE'
                                  AND l.expires_at >= :today
                                  AND l.expires_at <= :soon
                              )
                              OR (
                                  :status = 'available'
                                  AND l.status = 'AVAILABLE'
                                  AND l.expires_at > :soon
                              )
                          )
                        ORDER BY l.expires_at, i.name, l.id
                        LIMIT :size OFFSET :offset
                        """)
                .param("today", today)
                .param("soon", soon)
                .param("query", normalizedQuery)
                .param("pattern", "%" + normalizedQuery + "%")
                .param("status", normalizedStatus)
                .param("size", size)
                .param("offset", page * size)
                .query((rs, rowNum) ->
                        new InventoryLot(
                                rs.getString(1),
                                rs.getString(2),
                                rs.getString(3),
                                rs.getBigDecimal(4),
                                rs.getString(5),
                                rs.getObject(6, LocalDate.class),
                                rs.getObject(7, LocalDate.class),
                                rs.getString(8),
                                rs.getString(9)))
                .list();
    }

    public long countStockLots(String query, String status) {

        String normalizedQuery =
                query == null ? "" : query.trim().toLowerCase();

        String normalizedStatus =
                status == null ? "all" : status.trim().toLowerCase();

        LocalDate today = LocalDate.now();
        LocalDate soon = today.plusDays(7);

        return scopedSql("""
                        SELECT COUNT(*)
                        FROM stock_lots l
                        JOIN ingredients i
                            ON i.id = l.ingredient_id
                           AND i.kitchen_id = l.kitchen_id
                        WHERE l.kitchen_id = :workspaceKitchen
                          AND l.location_id = :workspaceLocation
                          AND l.quantity_remaining > 0
                          AND (
                              :query = ''
                              OR LOWER(i.name) LIKE :pattern
                              OR LOWER(l.id) LIKE :pattern
                          )
                          AND (
                              :status = 'all'
                              OR (
                                  :status = 'quarantined'
                                  AND (
                                      l.status = 'QUARANTINED'
                                      OR l.expires_at IS NULL
                                  )
                              )
                              OR (
                                  :status = 'expired'
                                  AND l.status = 'AVAILABLE'
                                  AND l.expires_at < :today
                              )
                              OR (
                                  :status = 'expiring'
                                  AND l.status = 'AVAILABLE'
                                  AND l.expires_at >= :today
                                  AND l.expires_at <= :soon
                              )
                              OR (
                                  :status = 'available'
                                  AND l.status = 'AVAILABLE'
                                  AND l.expires_at > :soon
                              )
                          )
                        """)
                .param("today", today)
                .param("soon", soon)
                .param("query", normalizedQuery)
                .param("pattern", "%" + normalizedQuery + "%")
                .param("status", normalizedStatus)
                .query(Long.class)
                .single();
    }

    public InventorySummary inventorySummary() {

        LocalDate today = LocalDate.now();
        LocalDate soon = today.plusDays(7);

        return scopedSql("""
                        SELECT
                            COUNT(DISTINCT CASE
                                WHEN status = 'AVAILABLE'
                                 AND expires_at >= :today
                                THEN ingredient_id
                            END),
                            COALESCE(SUM(CASE
                                WHEN status = 'AVAILABLE'
                                 AND expires_at >= :today
                                THEN 1 ELSE 0
                            END), 0),
                            COALESCE(SUM(CASE
                                WHEN status = 'AVAILABLE'
                                 AND expires_at >= :today
                                 AND expires_at <= :soon
                                THEN 1 ELSE 0
                            END), 0),
                            COALESCE(SUM(CASE
                                WHEN status = 'AVAILABLE'
                                 AND expires_at < :today
                                THEN 1 ELSE 0
                            END), 0)
                        FROM stock_lots
                        WHERE kitchen_id = :workspaceKitchen
                          AND location_id = :workspaceLocation
                          AND quantity_remaining > 0
                        """)
                .param("today", today)
                .param("soon", soon)
                .query((rs, rowNum) ->
                        new InventorySummary(
                                rs.getLong(1),
                                rs.getLong(2),
                                rs.getLong(3),
                                rs.getLong(4)))
                .single();
    }

    // ============================================================
    // ORDERS
    // ============================================================

    public List<Order> orders() {
        return scopedSql("""
                        SELECT id, total, status, created_at
                        FROM customer_orders
                        WHERE kitchen_id = :workspaceKitchen
                          AND location_id = :workspaceLocation
                        ORDER BY created_at DESC
                        """)
                .query((rs, rowNum) ->
                        new Order(
                                rs.getString(1),
                                orderItems(rs.getString(1)),
                                rs.getBigDecimal(2),
                                Order.Status.valueOf(rs.getString(3)),
                                rs.getObject(4, OffsetDateTime.class)
                                        .toInstant()))
                .list();
    }

    public Optional<Order> order(String id) {
        return scopedSql("""
                        SELECT id, total, status, created_at
                        FROM customer_orders
                        WHERE id = :id
                          AND kitchen_id = :workspaceKitchen
                          AND location_id = :workspaceLocation
                        """)
                .param("id", id)
                .query((rs, rowNum) ->
                        new Order(
                                rs.getString(1),
                                orderItems(rs.getString(1)),
                                rs.getBigDecimal(2),
                                Order.Status.valueOf(rs.getString(3)),
                                rs.getObject(4, OffsetDateTime.class)
                                        .toInstant()))
                .optional();
    }

    public List<OrderListItem> searchOrders(
            String query,
            String status,
            int page,
            int size) {

        String normalizedQuery =
                query == null ? "" : query.trim().toLowerCase();

        String normalizedStatus =
                status == null ? "ALL" : status.trim().toUpperCase();

        return scopedSql("""
                        SELECT o.id,
                               o.total,
                               o.status,
                               o.created_at,
                               COALESCE(SUM(oi.quantity), 0) AS item_count
                        FROM customer_orders o
                        LEFT JOIN order_items oi
                            ON oi.order_id = o.id
                        WHERE o.kitchen_id = :workspaceKitchen
                          AND o.location_id = :workspaceLocation
                          AND (
                            :query = ''
                            OR LOWER(o.id) LIKE :pattern
                        )
                        AND (
                            :status = 'ALL'
                            OR o.status = :status
                        )
                        GROUP BY o.id, o.total, o.status, o.created_at
                        ORDER BY o.created_at DESC, o.id DESC
                        LIMIT :size OFFSET :offset
                        """)
                .param("query", normalizedQuery)
                .param("pattern", "%" + normalizedQuery + "%")
                .param("status", normalizedStatus)
                .param("size", size)
                .param("offset", page * size)
                .query((rs, rowNum) ->
                        new OrderListItem(
                                rs.getString(1),
                                rs.getBigDecimal(2),
                                Order.Status.valueOf(rs.getString(3)),
                                rs.getObject(4, OffsetDateTime.class)
                                        .toInstant(),
                                rs.getLong(5)))
                .list();
    }

    public long countOrders(String query, String status) {

        String normalizedQuery =
                query == null ? "" : query.trim().toLowerCase();

        String normalizedStatus =
                status == null ? "ALL" : status.trim().toUpperCase();

        return scopedSql("""
                        SELECT COUNT(*)
                        FROM customer_orders
                        WHERE kitchen_id = :workspaceKitchen
                          AND location_id = :workspaceLocation
                          AND (
                            :query = ''
                            OR LOWER(id) LIKE :pattern
                        )
                        AND (
                            :status = 'ALL'
                            OR status = :status
                        )
                        """)
                .param("query", normalizedQuery)
                .param("pattern", "%" + normalizedQuery + "%")
                .param("status", normalizedStatus)
                .query(Long.class)
                .single();
    }

    public OrderSummary orderSummary() {
        return scopedSql("""
                        SELECT
                            SUM(CASE
                                WHEN status IN ('QUEUED', 'PREPARING')
                                THEN 1 ELSE 0 END),

                            SUM(CASE
                                WHEN status = 'DONE'
                                THEN 1 ELSE 0 END),

                            SUM(CASE
                                WHEN status = 'DONE'
                                 AND CAST(updated_at AS DATE) = CURRENT_DATE
                                THEN 1 ELSE 0 END),

                            COALESCE(SUM(CASE
                                WHEN status = 'DONE'
                                 AND CAST(updated_at AS DATE) = CURRENT_DATE
                                THEN total ELSE 0 END), 0)

                        FROM customer_orders
                        WHERE kitchen_id = :workspaceKitchen
                          AND location_id = :workspaceLocation
                        """)
                .query((rs, rowNum) ->
                        new OrderSummary(
                                rs.getLong(1),
                                rs.getLong(2),
                                rs.getLong(3),
                                rs.getBigDecimal(4)))
                .single();
    }

    @Transactional
    public Order updateOrderStatus(
            String id,
            Order.Status status) {

        return updateOrderStatus(id, status, "order-api");
    }

    @Transactional
    public Order updateOrderStatus(
            String id,
            Order.Status status,
            String actor) {

        Instant changedAt = Instant.now();
        orderTransitions.transition(
                workspace.kitchenId(),
                workspace.locationId(),
                id,
                status,
                changedAt,
                actor,
                "Advanced through the operational order API.",
                null
        );

        addActivity(
                "Order",
                id + " moved to "
                        + status.name().toLowerCase().replace('_', ' '));

        return order(id).orElseThrow();
    }

    private List<Order.OrderItem> orderItems(String id) {
        return scopedSql("""
                        SELECT item.dish_id, item.recipe_version_id,
                               item.quantity, item.unit_price
                        FROM order_items item
                        JOIN customer_orders orders ON orders.id = item.order_id
                        WHERE item.order_id = :id
                          AND orders.kitchen_id = :workspaceKitchen
                          AND orders.location_id = :workspaceLocation
                        ORDER BY item.line_number
                        """)
                .param("id", id)
                .query((rs, rowNum) ->
                        new Order.OrderItem(
                                rs.getString(1),
                                rs.getString(2),
                                rs.getInt(3),
                                rs.getBigDecimal(4)))
                .list();
    }

    @Transactional
    public Order saveOrder(Order order) {

        order.items().forEach(item ->
                requireWorkspaceRecipeForDish(
                        item.recipeVersionId(),
                        item.dishId()
                ));

        jdbc.sql("""
                        INSERT INTO customer_orders
                        (id, total, status, created_at, required_at,
                         kitchen_id, location_id)
                        VALUES (:id, :total, :status, :created, :created,
                                :kitchen, :location)
                        """)
                .param("id", order.id())
                .param("total", order.total())
                .param("status", order.status().name())
                .param("created", JdbcTimestamp.utc(order.createdAt()))
                .param("kitchen", workspace.kitchenId())
                .param("location", workspace.locationId())
                .update();

        for (int index = 0; index < order.items().size(); index++) {

            Order.OrderItem item = order.items().get(index);

            int inserted = scopedSql("""
                            INSERT INTO order_items
                            (order_id, line_number, dish_id, recipe_version_id,
                             quantity, unit_price)
                            SELECT orders.id, :line, :dish, :recipe, :quantity, :price
                            FROM customer_orders orders
                            WHERE orders.id = :orderId
                              AND orders.kitchen_id = :workspaceKitchen
                              AND orders.location_id = :workspaceLocation
                            """)
                    .param("orderId", order.id())
                    .param("line", index + 1)
                    .param("dish", item.dishId())
                    .param("recipe", item.recipeVersionId())
                    .param("quantity", item.quantity())
                    .param("price", item.unitPrice())
                    .update();
            if (inserted != 1) {
                throw new IllegalStateException(
                        "Order left this workspace before its lines were recorded."
                );
            }
        }

        int totalItems = order.items()
                .stream()
                .mapToInt(Order.OrderItem::quantity)
                .sum();

        OutboxEvent accepted = outbox.append(new OutboxEventDraft(
                null,
                "ORDER_ACCEPTED",
                1,
                workspace.kitchenId(),
                "order",
                order.id(),
                order.createdAt(),
                order.id(),
                null,
                "order-accepted:" + order.id(),
                order,
                Map.of("component", "order-service")
        ));

        appendOrderIngredientDemandEvents(order, accepted.eventId());
        addActivity(
                "Order",
                "Created " + order.id()
                        + " with " + totalItems + " items");

        return order;
    }

    private void appendOrderIngredientDemandEvents(
            Order order,
            String causationId
    ) {
        for (int index = 0; index < order.items().size(); index++) {
            Order.OrderItem item = order.items().get(index);
            int lineNumber = index + 1;
            RecipeVersion recipe = recipe(item.recipeVersionId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Accepted order recipe version disappeared: "
                                    + item.recipeVersionId()
                    ));
            if (recipe.yieldQuantity() == null
                    || recipe.yieldQuantity().signum() <= 0) {
                throw new IllegalStateException(
                        "Accepted recipe yield must be a positive count."
                );
            }
            CanonicalQuantity canonicalYield;
            try {
                canonicalYield = units.convert(
                        recipe.yieldQuantity(),
                        recipe.yieldUnit(),
                        "each"
                );
            } catch (IncompatibleUnitException error) {
                throw new IllegalStateException(
                        "Accepted recipe yield must use a count unit.",
                        error
                );
            }

            for (RecipeVersion.RecipeIngredient ingredient
                    : recipe.ingredients()) {
                Ingredient catalogIngredient = ingredient(ingredient.ingredientId())
                        .orElseThrow(() -> new IllegalStateException(
                                "Accepted recipe ingredient is outside this kitchen: "
                                        + ingredient.ingredientId()
                        ));
                CanonicalQuantity canonicalRecipeQuantity =
                        units.toIngredientBase(
                                catalogIngredient,
                                ingredient.quantity(),
                                ingredient.unit()
                        );
                BigDecimal canonicalDemand = canonicalRecipeQuantity.quantity()
                        .multiply(
                                BigDecimal.valueOf(item.quantity()),
                                MathContext.DECIMAL128
                        )
                        .divide(
                                canonicalYield.quantity(),
                                MathContext.DECIMAL128
                        )
                        .setScale(6, RoundingMode.HALF_UP)
                        .stripTrailingZeros();
                String entityId = order.id() + ":" + lineNumber + ":"
                        + ingredient.ingredientId();
                outbox.append(new OutboxEventDraft(
                        null,
                        "ORDER_INGREDIENT_DEMAND",
                        1,
                        workspace.kitchenId(),
                        "order_ingredient_demand",
                        entityId,
                        order.createdAt(),
                        order.id(),
                        causationId,
                        "order-ingredient-demand:" + entityId,
                        new OrderIngredientDemandPayload(
                                order.id(),
                                lineNumber,
                                item.dishId(),
                                item.recipeVersionId(),
                                workspace.locationId(),
                                item.quantity(),
                                ingredient.ingredientId(),
                                ingredient.quantity(),
                                ingredient.unit(),
                                recipe.yieldQuantity(),
                                recipe.yieldUnit(),
                                canonicalDemand,
                                canonicalRecipeQuantity.unit(),
                                order.createdAt()
                        ),
                        Map.of("component", "order-service")
                ));
            }
        }
    }

    private record OrderIngredientDemandPayload(
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
            Instant requiredAt
    ) {
    }

    // ============================================================
    // FEEDBACK
    // ============================================================

    public Optional<Feedback> feedbackById(String id) {
        return scopedSql("""
                        SELECT feedback.id, feedback.recipe_id,
                               feedback.feedback_text, feedback.rating,
                               feedback.occurred_at, feedback.source
                        FROM feedback
                        WHERE feedback.id = :id
                          AND feedback.kitchen_id = :workspaceKitchen
                        """)
                .param("id", id)
                .query((rs, rowNum) ->
                        new Feedback(
                                rs.getString(1),
                                rs.getString(2),
                                rs.getString(3),
                                rs.getInt(4),
                                rs.getObject(5, LocalDate.class),
                                rs.getString(6)))
                .optional();
    }

    public List<Feedback> feedback(String recipeId) {

        String sql;

        if (recipeId == null) {
            sql = """
                    SELECT feedback.id, feedback.recipe_id,
                           feedback.feedback_text, feedback.rating,
                           feedback.occurred_at, feedback.source
                    FROM feedback
                    WHERE feedback.kitchen_id = :workspaceKitchen
                    ORDER BY feedback.occurred_at DESC
                    """;
        } else {
            sql = """
                    SELECT feedback.id, feedback.recipe_id,
                           feedback.feedback_text, feedback.rating,
                           feedback.occurred_at, feedback.source
                    FROM feedback
                    WHERE feedback.recipe_id = :recipe
                      AND feedback.kitchen_id = :workspaceKitchen
                    ORDER BY feedback.occurred_at DESC
                    """;
        }

        var query = scopedSql(sql);

        if (recipeId != null) {
            query.param("recipe", recipeId);
        }

        return query.query((rs, rowNum) ->
                        new Feedback(
                                rs.getString(1),
                                rs.getString(2),
                                rs.getString(3),
                                rs.getInt(4),
                                rs.getObject(5, LocalDate.class),
                                rs.getString(6)))
                .list();
    }

    @Transactional
    public Feedback saveFeedback(Feedback value) {

        int inserted = scopedSql("""
                        INSERT INTO feedback
                        (id, recipe_id, feedback_text,
                         rating, occurred_at, source, kitchen_id)
                        SELECT :id, recipe.id, :text,
                               :rating, :occurred, :source, :workspaceKitchen
                        FROM recipe_versions recipe
                        JOIN dishes dish
                          ON dish.id = recipe.dish_id
                         AND dish.kitchen_id = recipe.kitchen_id
                        WHERE recipe.id = :recipe
                          AND recipe.kitchen_id = :workspaceKitchen
                          AND recipe.active = TRUE
                          AND dish.active = TRUE
                          AND dish.active_recipe_version_id = recipe.id
                        """)
                .param("id", value.id())
                .param("recipe", value.recipeId())
                .param("text", value.text())
                .param("rating", value.rating())
                .param("occurred", value.occurredAt())
                .param("source", value.source())
                .update();
        if (inserted != 1) {
            throw new IllegalArgumentException(
                    "Recipe version was not found in this kitchen."
            );
        }

        addActivity(
                "Feedback",
                "Captured feedback for " + value.recipeId());

        return value;
    }

    public void deleteFeedback(String id) {
        scopedSql("""
                        DELETE FROM feedback
                        WHERE id = :id
                          AND kitchen_id = :workspaceKitchen
                        """)
                .param("id", id)
                .update();
    }

    // ============================================================
    // ACTIVITY
    // ============================================================

    public List<ActivityEvent> activities() {
        return scopedSql("""
                        SELECT id, event_type, description, occurred_at
                        FROM activity_events
                        WHERE kitchen_id = :workspaceKitchen
                          AND location_id = :workspaceLocation
                        ORDER BY occurred_at DESC
                        LIMIT 8
                        """)
                .query((rs, rowNum) ->
                        new ActivityEvent(
                                rs.getString(1),
                                rs.getString(2),
                                rs.getString(3),
                                rs.getObject(4, OffsetDateTime.class)
                                        .toInstant()))
                .list();
    }

    @Transactional
    public ActivityEvent addActivity(
            String type,
            String description) {

        ActivityEvent value = new ActivityEvent(
                UUID.randomUUID().toString(),
                type,
                description,
                Instant.now());

        scopedSql("""
                        INSERT INTO activity_events
                        (id, event_type, description, occurred_at,
                         kitchen_id, location_id)
                        VALUES
                        (:id, :type, :description, :occurred,
                         :workspaceKitchen, :workspaceLocation)
                        """)
                .param("id", value.id())
                .param("type", type)
                .param("description", description)
                .param("occurred", JdbcTimestamp.utc(value.occurredAt()))
                .update();

        outbox.append(new OutboxEventDraft(
                value.id(),
                "ACTIVITY_RECORDED",
                1,
                workspace.kitchenId(),
                "activity",
                value.id(),
                value.occurredAt(),
                value.id(),
                null,
                "activity:" + value.id(),
                Map.of(
                        "activityType", type,
                        "description", description
                ),
                Map.of("component", "operational-api")
        ));

        return value;
    }

    // ============================================================
    // AI AUDIT
    // ============================================================

    public void auditAiAction(
            String type,
            String input,
            String itemId,
            double confidence,
            String decision,
            String explanation) {

        scopedSql("""
                        INSERT INTO ai_actions
                        (id, action_type, original_input,
                         normalized_item_id, confidence,
                         decision, explanation, occurred_at,
                         kitchen_id, location_id)
                        VALUES
                        (:id, :type, :input,
                         :item, :confidence,
                         :decision, :explanation, :occurred,
                         :workspaceKitchen, :workspaceLocation)
                        """)
                .param("id", UUID.randomUUID().toString())
                .param("type", type)
                .param("input", input)
                .param("item", itemId)
                .param("confidence", confidence)
                .param("decision", decision)
                .param("explanation", explanation)
                .param("occurred", JdbcTimestamp.utc(Instant.now()))
                .update();
    }

    // ============================================================
    // INGREDIENT ALIASES
    // ============================================================

    public List<AliasMatch> aliases() {
        return scopedSql("""
                        SELECT a.alias_normalized,
                               a.ingredient_id,
                               i.name,
                               a.confidence,
                               a.source
                        FROM ingredient_aliases a
                        JOIN ingredients i
                            ON i.id = a.ingredient_id
                           AND i.kitchen_id = a.kitchen_id
                        WHERE a.kitchen_id = :workspaceKitchen
                        """)
                .query((rs, rowNum) ->
                        new AliasMatch(
                                rs.getString(1),
                                rs.getString(2),
                                rs.getString(3),
                                rs.getDouble(4),
                                rs.getString(5)))
                .list();
    }

    // ============================================================
    // RECEIPTS
    // ============================================================

    public List<ReceiptImport> receipts() {
        return scopedSql("""
                        SELECT id, original_filename, object_uri,
                               status, merchant, purchase_date,
                               total, created_at
                        FROM receipt_imports
                        WHERE kitchen_id = :workspaceKitchen
                          AND location_id = :workspaceLocation
                        ORDER BY created_at DESC
                        """)
                .query((rs, rowNum) -> receipt(rs))
                .list();
    }

    public Optional<ReceiptImport> receipt(String id) {
        return scopedSql("""
                        SELECT id, original_filename, object_uri,
                               status, merchant, purchase_date,
                               total, created_at
                        FROM receipt_imports
                        WHERE id = :id
                          AND kitchen_id = :workspaceKitchen
                          AND location_id = :workspaceLocation
                        """)
                .param("id", id)
                .query((rs, rowNum) -> receipt(rs))
                .optional();
    }

    // ============================================================
    // EXPERIMENTS
    // ============================================================

    public ExperimentResponse experiment(String reference) {

        String id = experimentId(reference);

        return scopedSql("""
                        SELECT d.name,
                               e.theme,
                               e.theme_count,
                               e.feedback_count,
                               e.metric_name,
                               e.current_value,
                               e.proposed_value,
                               e.value_unit,
                               e.test_duration_days,
                               e.approved_at,
                               e.status
                        FROM recipe_experiments e
                        JOIN dishes d
                            ON d.id = e.dish_id
                           AND d.kitchen_id = e.kitchen_id
                        WHERE e.id = :id
                          AND e.kitchen_id = :workspaceKitchen
                        """)
                .param("id", id)
                .query((rs, rowNum) ->
                        new ExperimentResponse(
                                rs.getString(1),
                                rs.getString(2),
                                rs.getInt(3),
                                rs.getInt(4),
                                rs.getString(5),
                                rs.getBigDecimal(6),
                                rs.getBigDecimal(7),
                                rs.getString(8),
                                rs.getInt(9),
                                instant(rs, "approved_at"),
                                ExperimentStatus.valueOf(
                                        rs.getString(11))))
                .optional()
                .orElseThrow(() ->
                        new IllegalArgumentException("Experiment not found"));
    }

    @Transactional
    public void approveExperiment(String reference) {

        approveExperiment(reference, "owner");
    }

    @Transactional
    public void approveExperiment(String reference, String actor) {

        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("Approver is required.");
        }

        String id = experimentId(reference);
        String status = scopedSql("""
                        SELECT status
                        FROM recipe_experiments
                        WHERE id = :id
                          AND kitchen_id = :workspaceKitchen
                        FOR UPDATE
                        """)
                .param("id", id)
                .query(String.class)
                .optional()
                .orElseThrow(() ->
                        new IllegalArgumentException("Experiment not found"));
        if ("ACTIVE".equals(status)) {
            return;
        }
        if (!"PROPOSED".equals(status)) {
            throw new IllegalStateException(
                    "Only a proposed experiment can be approved."
            );
        }
        Instant now = Instant.now();

        int changed = scopedSql("""
                        UPDATE recipe_experiments
                        SET status = 'ACTIVE',
                            approved_by = :actor,
                            approved_at = :now,
                            updated_at = :now,
                            version = version + 1
                        WHERE id = :id
                          AND kitchen_id = :workspaceKitchen
                          AND status = 'PROPOSED'
                        """)
                .param("actor", actor.trim())
                .param("now", JdbcTimestamp.utc(now))
                .param("id", id)
                .update();
        if (changed != 1) {
            throw new IllegalArgumentException("Experiment not found");
        }

        addActivity(
                "Recipe experiment",
                "Owner approved a controlled recipe experiment");
    }

    private String experimentId(String reference) {

        return scopedSql("""
                        SELECT id
                        FROM recipe_experiments
                        WHERE kitchen_id = :workspaceKitchen
                          AND (id = :reference OR dish_id = :reference)
                          AND status IN ('PROPOSED', 'ACTIVE')
                        ORDER BY
                            CASE
                                WHEN id = :reference THEN 0
                                ELSE 1
                            END,
                            CASE WHEN status = 'ACTIVE' THEN 0 ELSE 1 END,
                            updated_at DESC,
                            id
                        LIMIT 1
                        """)
                .param("reference", reference)
                .query(String.class)
                .optional()
                .orElseThrow(() ->
                        new IllegalArgumentException("Experiment not found"));
    }

    private void requireWorkspaceCategory(String categoryId) {
        if (categoryId != null && menuCategory(categoryId).isEmpty()) {
            throw new IllegalArgumentException(
                    "Menu category was not found in this kitchen."
            );
        }
    }

    private void requireWorkspaceDish(String dishId) {
        if (dish(dishId).isEmpty()) {
            throw new IllegalArgumentException(
                    "Dish was not found in this kitchen."
            );
        }
    }

    private void requireWorkspaceIngredient(String ingredientId) {
        if (ingredient(ingredientId).isEmpty()) {
            throw new IllegalArgumentException(
                    "Ingredient was not found in this kitchen."
            );
        }
    }

    private void requireWorkspaceRecipeForDish(
            String recipeId,
            String dishId
    ) {
        RecipeVersion recipe = recipe(recipeId).orElseThrow(() ->
                new IllegalArgumentException(
                        "Recipe version was not found in this kitchen."
                ));
        if (!recipe.dishId().equals(dishId)) {
            throw new IllegalArgumentException(
                    "Recipe version does not belong to the selected dish."
            );
        }
    }

    private JdbcClient.StatementSpec scopedSql(String sql) {
        return jdbc.sql(sql)
                .param("workspaceKitchen", workspace.kitchenId())
                .param("workspaceLocation", workspace.locationId());
    }

    // ============================================================
    // ROW MAPPERS
    // ============================================================

    private Instant instant(ResultSet rs, String column)
            throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private ReceiptImport receipt(ResultSet rs)
            throws SQLException {

        String id = rs.getString(1);

        List<ReceiptImport.ReceiptItem> items =
                scopedSql("""
                                SELECT id,
                                       raw_name,
                                       ingredient_id,
                                       canonical_name,
                                       quantity,
                                       unit,
                                       unit_price,
                                       confidence,
                                       selected
                                FROM receipt_items item
                                WHERE item.receipt_id = :id
                                  AND EXISTS (
                                    SELECT 1
                                    FROM receipt_imports receipt
                                    WHERE receipt.id = item.receipt_id
                                      AND receipt.kitchen_id = :workspaceKitchen
                                      AND receipt.location_id = :workspaceLocation)
                                ORDER BY item.id
                                """)
                        .param("id", id)
                        .query((line, rowNum) ->
                                new ReceiptImport.ReceiptItem(
                                        line.getString(1),
                                        line.getString(2),
                                        line.getString(3),
                                        line.getString(4),
                                        line.getBigDecimal(5),
                                        line.getString(6),
                                        line.getBigDecimal(7),
                                        line.getBigDecimal(8),
                                        line.getBoolean(9)))
                        .list();

        OffsetDateTime created =
                rs.getObject(8, OffsetDateTime.class);

        return new ReceiptImport(
                id,
                rs.getString(2),
                rs.getString(3),
                ReceiptImport.Status.valueOf(rs.getString(4)),
                rs.getString(5),
                rs.getObject(6, LocalDate.class),
                rs.getBigDecimal(7),
                created.toInstant(),
                items);
    }

    public record AliasMatch(
            String alias,
            String ingredientId,
            String canonicalName,
            double confidence,
            String source) {
    }

    private Ingredient ingredient(
            ResultSet rs,
            int rowNum) throws SQLException {

        return new Ingredient(
                rs.getString(1),
                rs.getString(2),
                rs.getString(3),
                rs.getBoolean(4));
    }

    private Dish dish(
            ResultSet rs,
            int rowNum) throws SQLException {

        return new Dish(
                rs.getString(1),
                rs.getString(2),
                rs.getBigDecimal(3),
                rs.getString(4),
                rs.getBoolean(5),
                rs.getString(6),
                rs.getString(7));
    }

    private StockLot stockLot(
            ResultSet rs,
            int rowNum) throws SQLException {

        return new StockLot(
                rs.getString(1),
                rs.getString(2),
                rs.getBigDecimal(3),
                rs.getString(4),
                rs.getObject(5, LocalDate.class),
                rs.getObject(6, LocalDate.class),
                rs.getString(7));
    }
}
