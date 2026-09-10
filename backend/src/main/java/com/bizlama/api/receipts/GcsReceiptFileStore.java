package com.bizlama.api.receipts;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;

@Component
@ConditionalOnProperty(
        name = "bizlama.receipts.storage-mode",
        havingValue = "gcs"
)
public class GcsReceiptFileStore implements ReceiptFileStore {

    private final Storage storage;
    private final String bucket;
    private final String readinessObject;

    public GcsReceiptFileStore(
            @Value("${bizlama.receipts.bucket}") String bucket,
            @Value("${bizlama.receipts.readiness-object}")
            String readinessObject
    ) {
        this(
                StorageOptions.getDefaultInstance().getService(),
                bucket,
                readinessObject
        );
    }

    GcsReceiptFileStore(Storage storage, String bucket) {
        this(storage, bucket, ".well-known/bizlama-receipt-readiness");
    }

    GcsReceiptFileStore(
            Storage storage,
            String bucket,
            String readinessObject
    ) {
        this.storage = storage;
        this.bucket = bucket;
        this.readinessObject = readinessObject;
    }

    boolean readinessObjectAccessible() {
        return storage.get(BlobId.of(bucket, readinessObject)) != null;
    }

    @Override
    public StoredReceipt store(
            String receiptId,
            MultipartFile file) {

        try {
            String objectName =
                    "receipts/" + receiptId + "/" +
                            safeName(file.getOriginalFilename());

            String contentType =
                    file.getContentType() == null
                            ? "application/octet-stream"
                            : file.getContentType();

            BlobInfo blob = BlobInfo.newBuilder(
                            BlobId.of(bucket, objectName))
                    .setContentType(contentType)
                    .build();

            Blob created = storage.create(
                    blob,
                    file.getBytes(),
                    Storage.BlobTargetOption.doesNotExist()
            );

            return new StoredReceipt(
                    "gs://" + bucket + "/" + objectName,
                    contentType,
                    objectName,
                    created.getGeneration()
            );

        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException(
                    "Could not upload receipt to Cloud Storage",
                    error
            );
        }
    }

    @Override
    public void delete(StoredReceipt receipt) {
        String expectedUri = "gs://" + bucket + "/" + receipt.storageKey();
        if (!expectedUri.equals(receipt.uri()) || receipt.generation() == null) {
            throw new IllegalArgumentException(
                    "Receipt does not belong to configured Cloud Storage"
            );
        }
        BlobId blob = BlobId.of(
                bucket,
                receipt.storageKey(),
                receipt.generation()
        );
        storage.delete(
                blob,
                Storage.BlobSourceOption.generationMatch(
                        receipt.generation()
                )
        );
    }

    private String safeName(String name) {
        return name == null
                ? "receipt"
                : name.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}