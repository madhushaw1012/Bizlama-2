package com.bizlama.api.receipts;

import org.springframework.web.multipart.MultipartFile;

public interface ReceiptFileStore {

    StoredReceipt store(String receiptId, MultipartFile file);

    void delete(StoredReceipt receipt);

    record StoredReceipt(
            String uri,
            String mimeType,
            String storageKey,
            Long generation
    ) {
    }
}