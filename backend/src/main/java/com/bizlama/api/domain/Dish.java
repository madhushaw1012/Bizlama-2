package com.bizlama.api.domain;

import java.math.BigDecimal;

public record Dish (
    String id,
    String name,
    BigDecimal price,
    String activeRecipeVersionId,
    boolean active,
    String categoryId,
    String categoryName) {

}
