package com.bizlama.api.domain;

public record Ingredient (
    String id,
    String name,
    String baseUnit,
    boolean active
) {

}
