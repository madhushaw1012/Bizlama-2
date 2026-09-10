package com.bizlama.api.domain;

import java.time.LocalDate;

public record Feedback (
    String id,
    String recipeId,
    String text,
    int rating,
    LocalDate occurredAt,
    String source
) {

}
