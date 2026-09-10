package com.bizlama.api.receipts;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "bizlama.receipts.ai.enabled",
        havingValue = "false",
        matchIfMissing = true
)
public class ReviewOnlyReceiptExtractor implements ReceiptExtractor {

    @Override
    public Extraction extract(
            String uri,
            String filename,
            String mimeType) {

        return new Extraction(
                "Manual review",
                null,
                null,
                List.of()
        );
    }
}
