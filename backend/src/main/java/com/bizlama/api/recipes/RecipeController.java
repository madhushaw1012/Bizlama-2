package com.bizlama.api.recipes;

import com.bizlama.api.domain.Dish;
import com.bizlama.api.domain.Ingredient;
import com.bizlama.api.domain.RecipeVersion;
import com.bizlama.api.quantity.CanonicalQuantity;
import com.bizlama.api.quantity.UnitConversionService;
import com.bizlama.api.store.OperationalRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/recipes")
public class RecipeController {

    private final OperationalRepository repository;
    private final UnitConversionService units;

    public RecipeController(
            OperationalRepository repository,
            UnitConversionService units
    ) {
        this.repository = repository;
        this.units = units;
    }

    @GetMapping
    public List<RecipeVersion> list() {
        return repository.recipes();
    }

    @PostMapping("/dishes")
    @ResponseStatus(HttpStatus.CREATED)
    public RecipeVersion createDish(
            @Valid @RequestBody CreateDishRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        if (repository.menuCategory(request.categoryId()).isEmpty()) {
            throw invalid("Choose a valid menu category.");
        }

        String dishId = slug(request.name());
        String recipeId = dishId + "-v1";
        Instant now = Instant.now();
        String actor = actor(jwt);
        CanonicalQuantity yield = canonicalYield(
                request.yieldQuantity(),
                request.yieldUnit()
        );

        Dish dish = new Dish(
                dishId,
                request.name().trim(),
                request.price(),
                recipeId,
                true,
                request.categoryId(),
                null
        );
        RecipeVersion recipe = new RecipeVersion(
                recipeId,
                dishId,
                1,
                canonicalIngredients(request.ingredients()),
                instructions(request.instructions()),
                yield.quantity(),
                yield.unit(),
                "OPERATOR_ENTERED",
                "Initial recipe",
                now,
                actor,
                true,
                actor,
                now,
                now,
                null,
                null
        );

        return repository.createDishWithRecipe(
                dish,
                request.preparationMinutes(),
                recipe
        );
    }

    @PostMapping("/dishes/{dishId}/proposals")
    @ResponseStatus(HttpStatus.CREATED)
    public RecipeVersion propose(
            @PathVariable String dishId,
            @Valid @RequestBody RecipeProposal request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        if (repository.dish(dishId).isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Dish not found."
            );
        }

        int version = repository.nextRecipeVersion(dishId);
        String id = dishId + "-v" + version + "-"
                + UUID.randomUUID().toString().substring(0, 5);
        CanonicalQuantity yield = canonicalYield(
                request.yieldQuantity(),
                request.yieldUnit()
        );

        return repository.saveRecipeProposal(new RecipeVersion(
                id,
                dishId,
                version,
                canonicalIngredients(request.ingredients()),
                instructions(request.instructions()),
                yield.quantity(),
                yield.unit(),
                "OPERATOR_ENTERED",
                request.changeReason().trim(),
                Instant.now(),
                actor(jwt),
                false,
                null,
                null,
                null,
                null,
                null
        ));
    }

    @PostMapping("/{recipeId}/activate")
    public RecipeVersion activate(
            @PathVariable String recipeId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        try {
            return repository.activateRecipe(recipeId, actor(jwt));
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    error.getMessage()
            );
        }
    }

    private List<RecipeVersion.RecipeIngredient> canonicalIngredients(
            List<IngredientLine> lines
    ) {
        if (new HashSet<>(
                lines.stream().map(IngredientLine::ingredientId).toList()
        ).size() != lines.size()) {
            throw invalid("Each ingredient can appear only once.");
        }

        return lines.stream().map(line -> {
            Ingredient ingredient = repository.ingredient(line.ingredientId())
                    .orElseThrow(() -> invalid(
                            "Unknown ingredient: " + line.ingredientId()
                    ));
            CanonicalQuantity canonical = units.toIngredientBase(
                    ingredient,
                    line.quantity(),
                    line.unit()
            );
            return new RecipeVersion.RecipeIngredient(
                    ingredient.id(),
                    canonical.quantity(),
                    canonical.unit()
            );
        }).toList();
    }

    private CanonicalQuantity canonicalYield(
            BigDecimal quantity,
            String unit
    ) {
        return units.convert(quantity, unit, "each");
    }

    private List<String> instructions(List<String> values) {
        return values.stream().map(String::trim).toList();
    }

    private String actor(Jwt jwt) {
        if (jwt == null) {
            return "local-owner";
        }
        String email = jwt.getClaimAsString("email");
        return email == null || email.isBlank()
                ? jwt.getSubject()
                : email;
    }

    private ResponseStatusException invalid(String message) {
        return new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                message
        );
    }

    public record RecipeProposal(
            @NotEmpty List<@Valid IngredientLine> ingredients,
            @NotEmpty List<@NotBlank String> instructions,
            @NotNull @Positive BigDecimal yieldQuantity,
            @NotBlank String yieldUnit,
            @NotBlank String changeReason
    ) {
    }

    public record IngredientLine(
            @NotBlank String ingredientId,
            @NotNull @Positive BigDecimal quantity,
            @NotBlank String unit
    ) {
    }

    public record CreateDishRequest(
            @NotBlank String name,
            @NotNull @Positive BigDecimal price,
            @NotBlank String categoryId,
            @Positive int preparationMinutes,
            @NotEmpty List<@Valid IngredientLine> ingredients,
            @NotEmpty List<@NotBlank String> instructions,
            @NotNull @Positive BigDecimal yieldQuantity,
            @NotBlank String yieldUnit
    ) {
    }

    private String slug(String name) {
        String base = name
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");

        return base + "-"
                + UUID.randomUUID().toString().substring(0, 8);
    }
}
