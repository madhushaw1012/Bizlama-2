package com.bizlama.api.outbox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Produces compact JSON with recursively sorted object keys. Array order is
 * intentionally preserved because it is part of the payload's meaning.
 */
@Component
public class CanonicalJson {

    private final ObjectMapper objectMapper;

    public CanonicalJson(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String write(Object value) {
        try {
            JsonNode tree = objectMapper.valueToTree(value);
            return objectMapper.writeValueAsString(sorted(tree));
        } catch (JsonProcessingException | IllegalArgumentException error) {
            throw new IllegalArgumentException(
                    "Outbox value could not be serialized as canonical JSON",
                    error);
        }
    }

    private JsonNode sorted(JsonNode node) {
        if (node == null || node.isNull() || node.isValueNode()) {
            return node;
        }

        if (node.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            node.forEach(child -> result.add(sorted(child)));
            return result;
        }

        ObjectNode result = objectMapper.createObjectNode();
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        names.sort(Comparator.naturalOrder());
        names.forEach(name -> result.set(name, sorted(node.get(name))));
        return result;
    }
}
