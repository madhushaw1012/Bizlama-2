package com.bizlama.api.receipts;

import com.bizlama.api.domain.ReceiptImport;
import com.bizlama.api.stock.ExpiryProvenance;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record ReceiptView(
        String id,
        String originalFilename,
        String objectUri,
        ReceiptImport.Status status,
        String merchant,
        LocalDate purchaseDate,
        BigDecimal total,
        Instant createdAt,
        int version,
        String failureCode,
        String failureMessage,
        Instant confirmedAt,
        List<Line> items
) {
    public record Line(
            String id,
            String rawName,
            String ingredientId,
            String canonicalName,
            BigDecimal quantity,
            String unit,
            BigDecimal sourceQuantity,
            String sourceUnit,
            BigDecimal unitPrice,
            BigDecimal confidence,
            boolean selected,
            LocalDate expiresAt,
            ExpiryProvenance expiryProvenance,
            ReviewStatus reviewStatus
    ) {
    }

    public enum ReviewStatus {
        PENDING,
        APPROVED,
        REJECTED
    }
}
