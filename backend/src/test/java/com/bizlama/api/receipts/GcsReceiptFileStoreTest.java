package com.bizlama.api.receipts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

class GcsReceiptFileStoreTest {

    @Test
    void createsWithNonOverwritePreconditionAndDeletesSameGeneration() {
        Storage storage = mock(Storage.class);
        Blob blob = mock(Blob.class);
        when(blob.getGeneration()).thenReturn(42L);
        when(storage.create(
                any(BlobInfo.class),
                any(byte[].class),
                any(Storage.BlobTargetOption.class)
        )).thenReturn(blob);
        GcsReceiptFileStore store =
                new GcsReceiptFileStore(storage, "private-receipts");

        byte[] content = "private-evidence".getBytes(StandardCharsets.UTF_8);
        ReceiptFileStore.StoredReceipt stored = store.store(
                "RCT-STRONG-ID",
                new MockMultipartFile(
                        "file",
                        "receipt 1.pdf",
                        "application/pdf",
                        content
                )
        );

        ArgumentCaptor<BlobInfo> info =
                ArgumentCaptor.forClass(BlobInfo.class);
        ArgumentCaptor<Storage.BlobTargetOption> createOption =
                ArgumentCaptor.forClass(Storage.BlobTargetOption.class);
        verify(storage).create(
                info.capture(),
                eq(content),
                createOption.capture()
        );
        assertThat(createOption.getValue())
                .isEqualTo(Storage.BlobTargetOption.doesNotExist());
        assertThat(info.getValue().getBlobId().getName())
                .isEqualTo("receipts/RCT-STRONG-ID/receipt_1.pdf");
        assertThat(stored.generation()).isEqualTo(42L);

        store.delete(stored);

        ArgumentCaptor<BlobId> deleted = ArgumentCaptor.forClass(BlobId.class);
        ArgumentCaptor<Storage.BlobSourceOption> deleteOption =
                ArgumentCaptor.forClass(Storage.BlobSourceOption.class);
        verify(storage).delete(deleted.capture(), deleteOption.capture());
        assertThat(deleted.getValue().getBucket())
                .isEqualTo("private-receipts");
        assertThat(deleted.getValue().getName())
                .isEqualTo(stored.storageKey());
        assertThat(deleted.getValue().getGeneration()).isEqualTo(42L);
        assertThat(deleteOption.getValue())
                .isEqualTo(Storage.BlobSourceOption.generationMatch(42L));
    }

    @Test
    void checksReadinessWithObjectGetInsteadOfBucketMetadata() {
        Storage storage = mock(Storage.class);
        BlobId sentinel = BlobId.of(
                "private-receipts",
                ".well-known/bizlama-receipt-readiness"
        );
        when(storage.get(sentinel)).thenReturn(mock(Blob.class));
        GcsReceiptFileStore store =
                new GcsReceiptFileStore(storage, "private-receipts");

        assertThat(store.readinessObjectAccessible()).isTrue();
        verify(storage).get(sentinel);
    }

    @Test
    void refusesCompensationForAnotherBucketOrUnknownGeneration() {
        GcsReceiptFileStore store =
                new GcsReceiptFileStore(mock(Storage.class), "private-receipts");

        assertThatThrownBy(() -> store.delete(
                new ReceiptFileStore.StoredReceipt(
                        "gs://another-bucket/receipts/a.pdf",
                        "application/pdf",
                        "receipts/a.pdf",
                        null
                )
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "Receipt does not belong to configured Cloud Storage"
                );
    }
}
