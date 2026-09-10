package com.bizlama.api.shelflife;

import java.util.Optional;

public interface ShelfLifeGuidanceProvider {
    Optional<ShelfLifeGuidance> findForIngredient(String ingredientId);
}
