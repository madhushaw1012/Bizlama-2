package com.bizlama.api.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class CanonicalJsonTest {

    private final CanonicalJson canonicalJson =
            new CanonicalJson(new ObjectMapper());

    @Test
    void recursivelySortsObjectKeysAndPreservesArrayOrder() {
        String json = canonicalJson.write(Map.of(
                "z", List.of(
                        Map.of("b", 2, "a", 1),
                        Map.of("d", 4, "c", 3)),
                "a", true));

        assertThat(json).isEqualTo(
                "{\"a\":true,\"z\":[{\"a\":1,\"b\":2},{\"c\":3,\"d\":4}]}");
    }
}
