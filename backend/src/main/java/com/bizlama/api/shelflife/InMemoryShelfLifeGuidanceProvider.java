package com.bizlama.api.shelflife;

import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "bizlama.shelf-life.mode",
        havingValue = "memory"
)
public class InMemoryShelfLifeGuidanceProvider
        implements ShelfLifeGuidanceProvider {

    @Override
    public Optional<ShelfLifeGuidance> findForIngredient(String ingredientId) {
        return Optional.empty();
    }
}