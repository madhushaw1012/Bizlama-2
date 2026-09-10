package com.bizlama.api.shelflife;

import java.time.LocalDate;

public record ShelfLifeGuidance(
        String ingredientId,
        int refrigeratedDays,
        String source
) {

    public LocalDate expiresOn(LocalDate purchasedAt) {
        return purchasedAt.plusDays(refrigeratedDays);
    }
}