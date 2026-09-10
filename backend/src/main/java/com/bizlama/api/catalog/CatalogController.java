package com.bizlama.api.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import com.bizlama.api.domain.Dish;
import com.bizlama.api.domain.Ingredient;
import com.bizlama.api.domain.MenuCategory;
import com.bizlama.api.store.OperationalRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class CatalogController {

    private final OperationalRepository store;

    public CatalogController(OperationalRepository store) {
        this.store = store;
    }

    @GetMapping("/ingredients")
    public List<Ingredient> ingredients() {
        return store.ingredients();
    }

    @PostMapping("/ingredients")
    @ResponseStatus(HttpStatus.CREATED)
    public Ingredient createIngredient(@Valid @RequestBody IngredientRequest request) {
        String id = request.id() == null || request.id().isBlank()
                ? slug(request.name())
                : request.id();

        if (store.ingredient(id).isPresent()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Ingredient already exists."
            );
        }

        Ingredient ingredient = new Ingredient(
                id,
                request.name(),
                request.baseUnit(),
                true
        );

        return store.saveIngredient(ingredient);
    }

    @PutMapping("/ingredients/{id}")
    public Ingredient updateIngredient(
            @PathVariable String id,
            @Valid @RequestBody IngredientRequest request
    ) {
        if (store.ingredient(id).isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Ingredient not found."
            );
        }

        Ingredient ingredient = new Ingredient(
                id,
                request.name(),
                request.baseUnit(),
                true
        );

        return store.saveIngredient(ingredient);
    }

    @DeleteMapping("/ingredients/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteIngredient(@PathVariable String id) {
        store.deleteIngredient(id);
    }

    @GetMapping("/dishes")
    public List<Dish> dishes() {
        return store.dishes();
    }

    @GetMapping("/dish-categories")
    public List<MenuCategory> dishCategories() {
        return store.menuCategories();
    }

    @PostMapping("/dishes")
    @ResponseStatus(HttpStatus.CREATED)
    public Dish createDish(@Valid @RequestBody DishRequest request) {
        String id = request.id() == null || request.id().isBlank()
                ? slug(request.name())
                : request.id();

        if (store.dish(id).isPresent()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Dish already exists."
            );
        }

        Dish dish = new Dish(
                id,
                request.name(),
                request.price(),
                null,
                true,
                request.categoryId(),
                null
        );

        return store.saveDish(dish);
    }

    @PutMapping("/dishes/{id}")
    public Dish updateDish(
            @PathVariable String id,
            @Valid @RequestBody DishRequest request
    ) {
        Dish previous = store.dish(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Dish not found."
                ));

        String categoryId = request.categoryId() == null
                ? previous.categoryId()
                : request.categoryId();

        Dish dish = new Dish(
                id,
                request.name(),
                request.price(),
                previous.activeRecipeVersionId(),
                true,
                categoryId,
                previous.categoryName()
        );

        return store.saveDish(dish);
    }

    @DeleteMapping("/dishes/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDish(@PathVariable String id) {
        store.deleteDish(id);
    }

    private String slug(String name) {
        return name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "")
                + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public record IngredientRequest(
            String id,
            @NotBlank String name,
            @NotBlank String baseUnit
    ) {}

    public record DishRequest(
            String id,
            @NotBlank String name,
            @NotNull BigDecimal price,
            String categoryId
    ) {}
}