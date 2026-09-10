package com.bizlama.api.receipts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Status;

class ReceiptStorageHealthIndicatorTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void localModeChecksAnActuallyWritablePath() {
        ReceiptStorageHealthIndicator indicator =
                new ReceiptStorageHealthIndicator(
                        "local",
                        temporaryDirectory.resolve("receipts").toString(),
                        provider());

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void gcsModeChecksObjectLevelReadinessSentinel() {
        GcsReceiptFileStore store = mock(GcsReceiptFileStore.class);
        ObjectProvider<GcsReceiptFileStore> stores = provider();
        when(stores.getIfAvailable()).thenReturn(store);
        ReceiptStorageHealthIndicator indicator =
                new ReceiptStorageHealthIndicator(
                        "gcs",
                        temporaryDirectory.toString(),
                        stores);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);

        when(store.readinessObjectAccessible()).thenReturn(true);
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider() {
        return mock(ObjectProvider.class);
    }
}
