package com.bizlama.api.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record ReceiptImport (
    String id,
    String originalFilename,
    String objectUri,
    Status status,
    String merchant,
    LocalDate purchaseDate,
    BigDecimal total,
    Instant createdAt,
   List<ReceiptItem> items ) {

    public enum Status {UPLOADED, EXTRACTING, REVIEW_REQUIRED, FAILED, CONFIRMED}
    public record ReceiptItem (
        String id,
        String rawName,
        String ingredientId,
        String canonicalName,
        BigDecimal quantity,
        String unit,
        BigDecimal unitPrice,
        BigDecimal confidence,
        boolean selected
    ) {}
}
