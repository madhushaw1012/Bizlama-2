package com.bizlama.api.explanations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

class ExplanationSupportTest {

    private final ExplanationSupport support = new ExplanationSupport(
            JsonMapper.builder().build(),
            new ExplanationRedactor()
    );

    @Test
    void validatesStrictSchemaAndRedactsPersonalData() {
        RecommendationExplanation.Content content = support.parse("""
                {
                  "summary": "Contact chef@example.com about the purchase.",
                  "drivers": ["Verified demand is higher than supply."],
                  "caveats": []
                }
                """);

        assertThat(content.summary()).contains("[redacted-email]");
        assertThat(content.drivers())
                .containsExactly("Verified demand is higher than supply.");
    }

    @Test
    void rejectsUnknownFieldsAndOversizedCollections() {
        assertThatThrownBy(() -> support.parse("""
                {
                  "summary": "Text",
                  "drivers": ["One"],
                  "caveats": [],
                  "proposedQuantity": 999
                }
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only summary, drivers, and caveats");

        assertThatThrownBy(() -> support.parse("""
                {
                  "summary": "Text",
                  "drivers": [],
                  "caveats": []
                }
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("drivers");
    }

    @Test
    void generatedContentCannotRestateNumericEvidence() {
        RecommendationExplanation.Content content = support.parse("""
                {
                  "summary": "Purchase 9 kg.",
                  "drivers": ["Calculated supply gap."],
                  "caveats": []
                }
                """);

        assertThatThrownBy(() -> support.validateGenerated(content))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not restate numeric evidence");
    }

    @Test
    void hashIncludesPromptVersion() {
        assertThat(support.hash("v1", "{\"a\":1}"))
                .hasSize(64)
                .isNotEqualTo(support.hash("v2", "{\"a\":1}"));
    }
}
