package com.bizlama.api.receipts;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Validates receipt evidence before it reaches local or Cloud Storage.
 */
@Component
public class ReceiptEvidenceValidator {

    private static final Map<String, Set<String>> ALLOWED_TYPES = Map.of(
            "jpg", Set.of("image/jpeg"),
            "jpeg", Set.of("image/jpeg"),
            "png", Set.of("image/png"),
            "webp", Set.of("image/webp"),
            "pdf", Set.of("application/pdf")
    );

    private final long maximumBytes;

    public ReceiptEvidenceValidator(
            @Value("${bizlama.receipts.max-size-bytes:10485760}")
            long maximumBytes
    ) {
        if (maximumBytes <= 0) {
            throw new IllegalArgumentException(
                    "Receipt maximum size must be positive."
            );
        }
        this.maximumBytes = maximumBytes;
    }

    public Evidence validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw invalid(
                    HttpStatus.BAD_REQUEST,
                    "Choose a non-empty receipt image or PDF."
            );
        }
        if (file.getSize() > maximumBytes) {
            throw invalid(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "Receipt exceeds the configured upload limit."
            );
        }

        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            throw invalid(HttpStatus.BAD_REQUEST, "Receipt filename is required.");
        }
        String basename = filename.replace('\\', '/');
        basename = basename.substring(basename.lastIndexOf('/') + 1);
        int dot = basename.lastIndexOf('.');
        if (dot <= 0 || dot == basename.length() - 1) {
            throw invalid(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Receipt must use a supported image or PDF extension."
            );
        }

        String extension = basename.substring(dot + 1).toLowerCase(Locale.ROOT);
        String contentType = normalizedContentType(file.getContentType());
        Set<String> validTypes = ALLOWED_TYPES.get(extension);
        if (validTypes == null || !validTypes.contains(contentType)) {
            throw invalid(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Receipt extension and MIME type do not identify a supported file."
            );
        }

        byte[] signature = readSignature(file);
        if (!matchesSignature(extension, signature)) {
            throw invalid(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Receipt content does not match its declared file type."
            );
        }

        return new Evidence(
                basename,
                contentType,
                file.getSize(),
                sha256(file)
        );
    }

    private byte[] readSignature(MultipartFile file) {
        try (InputStream input = file.getInputStream()) {
            return input.readNBytes(12);
        } catch (IOException error) {
            throw invalid(HttpStatus.BAD_REQUEST, "Receipt content cannot be read.");
        }
    }

    private String sha256(MultipartFile file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream input =
                         new DigestInputStream(file.getInputStream(), digest)) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException error) {
            throw invalid(HttpStatus.BAD_REQUEST, "Receipt content cannot be read.");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        }
    }

    private boolean matchesSignature(String extension, byte[] value) {
        return switch (extension) {
            case "jpg", "jpeg" -> startsWith(value, 0xff, 0xd8, 0xff);
            case "png" -> startsWith(
                    value, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
            );
            case "pdf" -> startsWith(value, 0x25, 0x50, 0x44, 0x46, 0x2d);
            case "webp" -> startsWith(value, 0x52, 0x49, 0x46, 0x46)
                    && value.length >= 12
                    && value[8] == 0x57
                    && value[9] == 0x45
                    && value[10] == 0x42
                    && value[11] == 0x50;
            default -> false;
        };
    }

    private boolean startsWith(byte[] value, int... signature) {
        if (value.length < signature.length) {
            return false;
        }
        for (int index = 0; index < signature.length; index++) {
            if ((value[index] & 0xff) != signature[index]) {
                return false;
            }
        }
        return true;
    }

    private String normalizedContentType(String value) {
        if (value == null) {
            return "";
        }
        return value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    private ResponseStatusException invalid(
            HttpStatus status,
            String message
    ) {
        return new ResponseStatusException(status, message);
    }

    public record Evidence(
            String filename,
            String contentType,
            long sizeBytes,
            String sha256
    ) {
    }

}
