package com.bizlama.api.receipts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

class LocalReceiptFileStoreTest {

    @TempDir
    private Path directory;

    @Test
    void createsOnceWithoutOverwritingAndDeletesExactStoredObject()
            throws Exception {
        LocalReceiptFileStore store =
                new LocalReceiptFileStore(directory.toString());
        MockMultipartFile original = file("original");
        ReceiptFileStore.StoredReceipt stored =
                store.store("RCT-STRONG-ID", original);
        Path target = Path.of(java.net.URI.create(stored.uri()));

        assertThat(Files.readString(target)).isEqualTo("original");

        assertThatThrownBy(() -> store.store(
                "RCT-STRONG-ID",
                file("replacement")
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("Could not store receipt");
        assertThat(Files.readString(target)).isEqualTo("original");

        store.delete(stored);
        assertThat(target).doesNotExist();
    }

    @Test
    void refusesToDeleteAnObjectOutsideItsConfiguredDirectory() {
        LocalReceiptFileStore store =
                new LocalReceiptFileStore(directory.toString());
        Path outside = directory.getParent().resolve("outside.pdf");

        assertThatThrownBy(() -> store.delete(
                new ReceiptFileStore.StoredReceipt(
                        outside.toUri().toString(),
                        "application/pdf",
                        outside.toString(),
                        null
                )
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Receipt does not belong to local storage");
    }

    private MockMultipartFile file(String body) {
        return new MockMultipartFile(
                "file",
                "receipt.pdf",
                "application/pdf",
                body.getBytes(StandardCharsets.UTF_8)
        );
    }
}
