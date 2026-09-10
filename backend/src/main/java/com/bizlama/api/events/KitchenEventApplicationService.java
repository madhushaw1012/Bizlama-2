package com.bizlama.api.events;

import com.bizlama.api.config.WorkspaceProperties;
import com.bizlama.api.domain.RecipeVersion;
import com.bizlama.api.domain.StockMovement;
import com.bizlama.api.shelflife.ShelfLifeGuidanceProvider;
import com.bizlama.api.stock.ExpiryProvenance;
import com.bizlama.api.stock.InventoryAllocationService;
import com.bizlama.api.stock.InventoryPurchaseService;
import com.bizlama.api.store.OperationalRepository;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KitchenEventApplicationService {

    private static final MathContext CALCULATION_CONTEXT = MathContext.DECIMAL128;

    private final OperationalRepository repository;
    private final ShelfLifeGuidanceProvider shelfLife;
    private final InventoryPurchaseService purchases;
    private final InventoryAllocationService allocations;
    private final JdbcClient jdbc;
    private final WorkspaceProperties workspace;

    public KitchenEventApplicationService(
            OperationalRepository repository,
            ShelfLifeGuidanceProvider shelfLife,
            InventoryPurchaseService purchases,
            InventoryAllocationService allocations,
            JdbcClient jdbc,
            WorkspaceProperties workspace
    ) {
        this.repository = repository;
        this.shelfLife = shelfLife;
        this.purchases = purchases;
        this.allocations = allocations;
        this.jdbc = jdbc;
        this.workspace = workspace;
    }

    @Transactional
    public void apply(
            ParsedKitchenEvent event,
            String proposalId,
            Instant actionAt
    ) {
        validate(event);

        switch (event.type()) {
            case PURCHASE -> {
                LocalDate purchased = kitchenDate(actionAt);
                var guidance = shelfLife
                        .findForIngredient(event.itemId())
                        .orElseThrow(() -> new IllegalStateException(
                                "Purchase expiry requires a reviewed shelf-life rule."
                        ));
                LocalDate expires = guidance.expiresOn(purchased);
                purchases.add(new InventoryPurchaseService.Purchase(
                        null,
                        workspace.kitchenId(),
                        workspace.locationId(),
                        event.itemId(),
                        event.quantity(),
                        event.unit(),
                        event.quantity(),
                        event.unit(),
                        purchased,
                        expires,
                        ExpiryProvenance.REVIEWED_SHELF_LIFE_RULE,
                        "kitchen-event:" + proposalId,
                        "kitchen-event",
                        proposalId,
                        null,
                        null,
                        actionAt
                ));
            }
            case WASTE -> allocations.allocate(
                    workspace.kitchenId(),
                    workspace.locationId(),
                    event.itemId(),
                    event.quantity(),
                    event.unit(),
                    actionAt,
                    StockMovement.MovementType.WASTE,
                    "waste-event",
                    proposalId
            );
            case PRODUCTION -> applyProduction(
                    event,
                    proposalId,
                    actionAt
            );
        }

        repository.addActivity(label(event.type()), event.summary());
    }

    @Transactional
    public void applyAll(
            List<ParsedKitchenEvent> events,
            String proposalId,
            Instant actionAt
    ) {
        if (events == null || events.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one kitchen event is required."
            );
        }
        for (ParsedKitchenEvent event : events) {
            apply(event, proposalId, actionAt);
        }
    }

    private void applyProduction(
            ParsedKitchenEvent event,
            String proposalId,
            Instant actionAt
    ) {
        RecipeVersion recipe = repository
                .dish(event.itemId())
                .flatMap(dish ->
                        repository.recipe(dish.activeRecipeVersionId())
                )
                .orElseThrow(() -> new IllegalStateException(
                        "No active recipe for " + event.item()
                ));

        BigDecimal yield = jdbc.sql("""
                        SELECT yield_quantity
                        FROM recipe_versions
                        WHERE id = :recipe
                          AND kitchen_id = :kitchen
                          AND active = TRUE
                        """)
                .param("recipe", recipe.id())
                .param("kitchen", workspace.kitchenId())
                .query(BigDecimal.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "Active recipe yield is unavailable."
                ));
        if (yield.signum() <= 0) {
            throw new IllegalStateException("Active recipe yield must be positive.");
        }

        for (RecipeVersion.RecipeIngredient ingredient : recipe.ingredients()) {
            BigDecimal required = ingredient.quantity()
                    .multiply(event.quantity(), CALCULATION_CONTEXT)
                    .divide(yield, CALCULATION_CONTEXT);
            allocations.allocate(
                    workspace.kitchenId(),
                    workspace.locationId(),
                    ingredient.ingredientId(),
                    required,
                    ingredient.unit(),
                    actionAt,
                    StockMovement.MovementType.PRODUCTION_CONSUMPTION,
                    "production-run",
                    proposalId
            );
        }
    }

    private LocalDate kitchenDate(Instant actionAt) {
        String timezone = jdbc.sql("""
                        SELECT timezone
                        FROM kitchens
                        WHERE id = :kitchen
                          AND active = TRUE
                        """)
                .param("kitchen", workspace.kitchenId())
                .query(String.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "Active kitchen timezone is unavailable."
                ));
        return actionAt.atZone(ZoneId.of(timezone)).toLocalDate();
    }

    private void validate(ParsedKitchenEvent event) {
        if (event == null || event.type() == null) {
            throw new IllegalArgumentException("Kitchen event type is required.");
        }
        if (event.itemId() == null || event.itemId().isBlank()) {
            throw new IllegalArgumentException("Kitchen event item is required.");
        }
        if (event.quantity() == null || event.quantity().signum() <= 0) {
            throw new IllegalArgumentException(
                    "Kitchen event quantity must be positive."
            );
        }
        if (event.unit() == null || event.unit().isBlank()) {
            throw new IllegalArgumentException("Kitchen event unit is required.");
        }
    }

    private String label(KitchenEventType type) {
        return switch (type) {
            case PURCHASE -> "Purchase";
            case PRODUCTION -> "Production";
            case WASTE -> "Waste";
        };
    }
}