package com.bizlama.api.ai;

import com.google.genai.types.GenerateContentConfig;
import java.util.Objects;

/**
 * Single fakeable boundary for every Gemini model call in the API.
 */
public interface GeminiModelClient {

    String generate(Request request);

    enum Operation {
        RECEIPT_EXTRACTION,
        RECOMMENDATION_EXPLANATION
    }

    sealed interface Input permits TextInput, GcsDocumentInput {
        String prompt();
    }

    record TextInput(String prompt) implements Input {
        public TextInput {
            prompt = requireText(prompt, "prompt");
        }
    }

    record GcsDocumentInput(
            String prompt,
            String uri,
            String mimeType
    ) implements Input {
        public GcsDocumentInput {
            prompt = requireText(prompt, "prompt");
            uri = requireText(uri, "uri");
            mimeType = requireText(mimeType, "mime type");
            if (!uri.startsWith("gs://")) {
                throw new IllegalArgumentException(
                        "Gemini document input must use a private gs:// URI"
                );
            }
        }
    }

    record Request(
            Operation operation,
            String model,
            Input input,
            GenerateContentConfig configuration
    ) {
        public Request {
            Objects.requireNonNull(operation, "operation");
            model = requireText(model, "model");
            Objects.requireNonNull(input, "input");
            Objects.requireNonNull(configuration, "configuration");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Gemini request " + field + " is required"
            );
        }
        return value.trim();
    }
}
