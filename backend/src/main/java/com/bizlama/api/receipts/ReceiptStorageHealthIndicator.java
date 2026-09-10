package com.bizlama.api.receipts;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** Verifies the actual receipt-storage dependency selected for this process. */
@Component("receiptStorage")
public class ReceiptStorageHealthIndicator implements HealthIndicator {

    private final String mode;
    private final Path localDirectory;
    private final ObjectProvider<GcsReceiptFileStore> cloudStores;

    public ReceiptStorageHealthIndicator(
            @Value("${bizlama.receipts.storage-mode:local}") String mode,
            @Value("${bizlama.receipts.local-directory:./.data/receipts}")
            String localDirectory,
            ObjectProvider<GcsReceiptFileStore> cloudStores
    ) {
        this.mode = mode == null ? "" : mode.trim().toLowerCase();
        this.localDirectory = Path.of(localDirectory)
                .toAbsolutePath()
                .normalize();
        this.cloudStores = cloudStores;
    }

    @Override
    public Health health() {
        return switch (mode) {
            case "local" -> localHealth();
            case "gcs" -> cloudHealth();
            default -> Health.down()
                    .withDetail("mode", mode)
                    .withDetail("error", "unsupported receipt storage mode")
                    .build();
        };
    }

    private Health localHealth() {
        try {
            Path candidate = Files.exists(localDirectory)
                    ? localDirectory
                    : localDirectory.getParent();
            while (candidate != null && !Files.exists(candidate)) {
                candidate = candidate.getParent();
            }
            boolean healthy = candidate != null
                    && Files.isDirectory(candidate)
                    && Files.isWritable(candidate);
            return healthy
                    ? Health.up()
                            .withDetail("mode", "local")
                            .withDetail("writable", true)
                            .build()
                    : Health.down()
                            .withDetail("mode", "local")
                            .withDetail("writable", false)
                            .build();
        } catch (RuntimeException failure) {
            return Health.down(failure)
                    .withDetail("mode", "local")
                    .build();
        }
    }

    private Health cloudHealth() {
        GcsReceiptFileStore store = cloudStores.getIfAvailable();
        if (store == null) {
            return Health.down()
                    .withDetail("mode", "gcs")
                    .withDetail("configuration", "GCS store bean missing")
                    .build();
        }
        try {
            return store.readinessObjectAccessible()
                    ? Health.up()
                            .withDetail("mode", "gcs")
                            .withDetail("readinessObject", "accessible")
                            .build()
                    : Health.down()
                            .withDetail("mode", "gcs")
                            .withDetail("readinessObject", "missing")
                            .build();
        } catch (RuntimeException failure) {
            return Health.down(failure)
                    .withDetail("mode", "gcs")
                    .build();
        }
    }
}
