package com.bizlama.api.receipts;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
@ConditionalOnProperty(
        name = "bizlama.receipts.storage-mode",
        havingValue = "local",
        matchIfMissing = true
)
public class LocalReceiptFileStore implements ReceiptFileStore {

    private final Path directory;

    public LocalReceiptFileStore(
            @Value("${bizlama.receipts.local-directory:./.data/receipts}")
            String directory) {

        this.directory = Path.of(directory)
                .toAbsolutePath()
                .normalize();
    }

    @Override
    public StoredReceipt store(String receiptId, MultipartFile file) {
        Path target = null;
        boolean created = false;
        try {
            Files.createDirectories(directory);
            String extension = extension(file.getOriginalFilename());
            target = directory.resolve(receiptId + extension).normalize();
            if (!target.startsWith(directory)) {
                throw new IllegalArgumentException(
                        "Unsafe receipt filename"
                );
            }

            try (InputStream input = file.getInputStream();
                    OutputStream output = Files.newOutputStream(
                            target,
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE
                    )) {
                created = true;
                input.transferTo(output);
            }

            return new StoredReceipt(
                    target.toUri().toString(),
                    contentType(file),
                    target.toString(),
                    null
            );
        } catch (IOException error) {
            if (created && target != null) {
                try {
                    Files.deleteIfExists(target);
                } catch (IOException cleanupError) {
                    error.addSuppressed(cleanupError);
                }
            }
            throw new IllegalStateException(
                    "Could not store receipt",
                    error
            );
        }
    }

    @Override
    public void delete(StoredReceipt receipt) {
        Path target;
        try {
            target = Path.of(URI.create(receipt.uri()))
                    .toAbsolutePath()
                    .normalize();
        } catch (RuntimeException error) {
            throw new IllegalArgumentException(
                    "Receipt does not belong to local storage",
                    error
            );
        }
        if (!target.startsWith(directory)
                || !target.toString().equals(receipt.storageKey())) {
            throw new IllegalArgumentException(
                    "Receipt does not belong to local storage"
            );
        }
        try {
            Files.deleteIfExists(target);
        } catch (IOException error) {
            throw new IllegalStateException(
                    "Could not delete stored receipt",
                    error
            );
        }
    }

    private String extension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }

        String value = filename
                .substring(filename.lastIndexOf('.'))
                .toLowerCase();

        return value.matches(
                "\\.(jpg|jpeg|png|webp|pdf|txt)"
        ) ? value : "";
    }

    private String contentType(MultipartFile file) {
        return file.getContentType() == null
                ? "application/octet-stream"
                : file.getContentType();
    }
}