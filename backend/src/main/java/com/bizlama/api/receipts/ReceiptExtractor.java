package com.bizlama.api.receipts;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface ReceiptExtractor {

    default String provider() {
        return "deterministic-review";
    }

    default String model() {
        return "manual-review";
    }

    default String schemaVersion() {
        return "receipt-extraction-v1";
    }

    Extraction extract(
            String uri,
            String filename,
            String mimeType
    );

    record Extraction(
            String merchant,
            LocalDate purchaseDate,
            BigDecimal total,
            List<Line> lines
    ) {
    }

    record Line(
            String rawName,
            BigDecimal quantity,
            String unit,
            BigDecimal unitPrice,
            BigDecimal confidence
    ) {
    }
}