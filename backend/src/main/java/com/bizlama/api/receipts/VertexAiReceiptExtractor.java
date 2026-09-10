package com.bizlama.api.receipts;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.Part;

@Component
@ConditionalOnProperty(
        name = "bizlama.receipts.ai-enabled",
        havingValue = "true"
)
public class VertexAiReceiptExtractor implements ReceiptExtractor {

    private final ObjectMapper mapper;
    private final String model;

    public VertexAiReceiptExtractor(
            ObjectMapper mapper,
            @Value("${bizlama.receipts.model:gemini-2.5-flash}")
            String model) {
        this.mapper = mapper;
        this.model = model;
    }

    @Override
    public String provider() {
        return "vertex-ai";
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public Extraction extract(
            String uri,
            String filename,
            String mimeType) {

        String prompt = """
                Extract this grocery receipt.
                Return only JSON with merchant,
                purchaseDate (YYYY-MM-DD or null), total, and items.
                Each item must have rawName, quantity, unit
                (g, kg, ml, L, or pieces), unitPrice,
                and confidence from 0 to 1.
                Do not invent unreadable values.
                """;

        try (Client client = new Client()) {

            Content content = Content.fromParts(
                    Part.fromText(prompt),
                    Part.fromUri(uri, mimeType)
            );

            String json = client.models
                    .generateContent(model, content, null)
                    .text()
                    .replaceAll("^```json\\s*|\\s*```$", "");

            JsonNode root = mapper.readTree(json);

            List<Line> lines = new ArrayList<>();

            root.path("items").forEach(item ->
                    lines.add(
                            new Line(
                                    requiredText(item, "rawName"),
                                    requiredPositiveDecimal(item, "quantity"),
                                    requiredText(item, "unit"),
                                    decimal(item.get("unitPrice")),
                                    requiredConfidence(item)
                            )
                    )
            );

            LocalDate date =
                    root.path("purchaseDate").isTextual()
                            ? LocalDate.parse(
                                    root.path("purchaseDate").asText()
                            )
                            : null;

            JsonNode merchantNode = root.get("merchant");
            String merchant = merchantNode != null && merchantNode.isTextual()
                    && !merchantNode.asText().isBlank()
                    ? merchantNode.asText().trim()
                    : null;

            return new Extraction(
                    merchant,
                    date,
                    decimal(root.get("total")),
                    lines
            );

        } catch (Exception error) {
            throw new IllegalStateException(
                    "Vertex AI could not extract the receipt",
                    error
            );
        }
    }

    private BigDecimal decimal(JsonNode node) {
        return node == null || node.isNull()
                ? null
                : node.decimalValue();
    }

    private String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalStateException(
                    "Receipt extraction omitted required field " + field
            );
        }
        return value.asText().trim();
    }

    private BigDecimal requiredPositiveDecimal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber()) {
            throw new IllegalStateException(
                    "Receipt extraction omitted required numeric field " + field
            );
        }
        BigDecimal decimal = value.decimalValue();
        if (decimal.signum() <= 0) {
            throw new IllegalStateException(
                    "Receipt extraction returned a non-positive " + field
            );
        }
        return decimal;
    }

    private BigDecimal requiredConfidence(JsonNode node) {
        JsonNode value = node.get("confidence");
        if (value == null || !value.isNumber()) {
            throw new IllegalStateException(
                    "Receipt extraction omitted required numeric field confidence"
            );
        }
        BigDecimal confidence = value.decimalValue();
        if (confidence.compareTo(BigDecimal.ZERO) < 0
                || confidence.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalStateException(
                    "Receipt extraction confidence must be between 0 and 1"
            );
        }
        return confidence;
    }
}