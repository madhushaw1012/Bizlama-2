package com.bizlama.api.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bizlama.api.outbox.CanonicalJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SignalProposalParserTest {

    private SignalProposalParser parser;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        parser = new SignalProposalParser(mapper, new CanonicalJson(mapper));
    }

    @Test
    void acceptsExactSignalEngineShortageContract() {
        SignalProposalFixtures.Fixture fixture = SignalProposalFixtures.shortage(
                "kitchen-default", "ingredient-rice");

        SignalProposalCommand command = parser.parse(
                fixture.body(), SignalProposalFixtures.TARGET, 65_536);
        parser.validateAttributes(fixture.attributes(), command);

        assertThat(command.proposalId()).isEqualTo(fixture.proposalId());
        assertThat(command.suggestedQuantity()).isEqualByComparingTo("200");
        assertThat(command.locationId()).isEqualTo("location-main");
        assertThat(command.directMutationAllowed()).isFalse();
        assertThat(command.commandSha256()).hasSize(64);
        SignalProposalCommand plainNumber = parser.parse(
                text(fixture.body()).replace("2E+2", "200")
                        .getBytes(StandardCharsets.UTF_8),
                SignalProposalFixtures.TARGET,
                65_536);
        assertThat(plainNumber.commandSha256())
                .isEqualTo(command.commandSha256());
    }

    @Test
    void rejectsUnknownOrDuplicateFieldsAndExecutableCommands() {
        String valid = text(SignalProposalFixtures.shortage(
                "kitchen-default", "ingredient-rice").body());

        assertCode(
                valid.replace(
                        "\"directMutationAllowed\":false}",
                        "\"directMutationAllowed\":false,\"unknown\":1}"),
                "INVALID_SCHEMA");
        assertCode(
                valid.replace(
                        "\"schemaVersion\":1,",
                        "\"schemaVersion\":1,\"schemaVersion\":1,"),
                "MALFORMED_JSON");
        assertCode(
                valid.replace(
                        "\"directMutationAllowed\":false",
                        "\"directMutationAllowed\":true"),
                "DIRECT_MUTATION_FORBIDDEN");
    }

    @Test
    void rejectsQuantityEvidenceAndBrokerAttributeTampering() {
        SignalProposalFixtures.Fixture fixture = SignalProposalFixtures.shortage(
                "kitchen-default", "ingredient-rice");
        assertCode(
                text(fixture.body()).replace(
                        "\"proposedQuantity\":2E+2",
                        "\"proposedQuantity\":201"),
                "INVALID_SUGGESTED_QUANTITY");

        SignalProposalCommand command = parser.parse(
                fixture.body(), SignalProposalFixtures.TARGET, 65_536);
        assertThatThrownBy(() -> parser.validateAttributes(
                java.util.Map.of(
                        "proposalId", "SIG-000000000000000000000000",
                        "signalTime", fixture.attributes().get("signalTime"),
                        "schemaVersion", "1",
                        "commandType", SignalProposalParser.COMMAND_TYPE,
                        "kitchenId", fixture.attributes().get("kitchenId"),
                        "locationId", fixture.attributes().get("locationId")),
                command))
                .isInstanceOf(SignalProposalValidationException.class)
                .extracting(error -> ((SignalProposalValidationException) error).code())
                .isEqualTo("ATTRIBUTE_BODY_MISMATCH");
    }

    @Test
    void rejectsWrongQueueAndNonCanonicalEvidence() {
        String valid = text(SignalProposalFixtures.shortage(
                "kitchen-default", "ingredient-rice").body());
        assertCode(
                valid.replace(SignalProposalFixtures.TARGET, "projects/bizlm-prod/topics/other"),
                "WRONG_TARGET_QUEUE");
        assertCode(
                valid.replace("eventCount\\\":2", "eventCount\\\":02"),
                "INVALID_EVIDENCE");
    }

    private void assertCode(String body, String expectedCode) {
        assertThatThrownBy(() -> parser.parse(
                body.getBytes(StandardCharsets.UTF_8),
                SignalProposalFixtures.TARGET,
                65_536))
                .isInstanceOf(SignalProposalValidationException.class)
                .extracting(error -> ((SignalProposalValidationException) error).code())
                .isEqualTo(expectedCode);
    }

    private static String text(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }
}
