package com.bizlama.api.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bizlama.api.outbox.CanonicalJson;
import com.bizlama.api.signals.SignalProposalService.IngestOutcome;
import com.bizlama.api.signals.SignalProposalService.IngestResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("memory")
@Transactional
class SignalProposalServiceIntegrationTest {

    private static final String KITCHEN = "kitchen-default";
    private static final String LOCATION = "location-main";
    private static final Instant PUBLISH_TIME =
            Instant.parse("2026-09-09T13:01:00Z");

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private SignalProposalParser parser;

    @Autowired
    private SignalProposalService service;

    @Autowired
    private CanonicalJson canonicalJson;

    private SignalProposalProcessor processor;
    private String ingredientId;

    @BeforeEach
    void setUp() {
        ingredientId = "signal-ingredient-" + UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO ingredients
                        (id, name, base_unit, active, kitchen_id)
                        VALUES (:id, 'Signal bridge ingredient', 'g', TRUE, :kitchen)
                        """)
                .param("id", ingredientId)
                .param("kitchen", KITCHEN)
                .update();
        processor = new SignalProposalProcessor(
                new SignalProposalProperties(
                        true,
                        SignalProposalFixtures.PROJECT,
                        SignalProposalFixtures.SUBSCRIPTION,
                        SignalProposalFixtures.TARGET,
                        65_536),
                parser,
                service,
                canonicalJson,
                new SimpleMeterRegistry());
    }

    @Test
    void persistsOnceAsPendingEvidenceAndSupportsAuditedDismissalOnly() {
        long recommendationsBefore = count("recommendations");
        long movementsBefore = count("stock_movements");
        SignalProposalFixtures.Fixture fixture = SignalProposalFixtures.shortage(
                KITCHEN, ingredientId);

        IngestResult first = processor.process(
                fixture.body(),
                "signal-message-1",
                PUBLISH_TIME,
                fixture.attributes(),
                null);
        IngestResult replay = processor.process(
                fixture.body(),
                "signal-message-2",
                PUBLISH_TIME,
                fixture.attributes(),
                2);
        IngestResult exactRedelivery = processor.process(
                fixture.body(),
                "signal-message-1",
                PUBLISH_TIME,
                fixture.attributes(),
                3);

        assertThat(first.outcome()).isEqualTo(IngestOutcome.ACCEPTED);
        assertThat(replay.outcome()).isEqualTo(IngestOutcome.DUPLICATE);
        assertThat(exactRedelivery.outcome()).isEqualTo(IngestOutcome.DUPLICATE);
        assertThat(count("governed_signal_proposals")).isOne();
        assertThat(count("signal_proposal_deliveries")).isEqualTo(2);
        assertThat(jdbc.sql("""
                        SELECT COUNT(*) FROM analytics_outbox
                        WHERE event_type = 'GOVERNED_SIGNAL_PROPOSAL_RECEIVED'
                          AND aggregate_id = :id
                        """)
                .param("id", fixture.proposalId())
                .query(Long.class)
                .single()).isOne();

        SignalProposalView pending = service.get(fixture.proposalId());
        assertThat(pending.status()).isEqualTo("PENDING");
        assertThat(pending.version()).isOne();
        assertThat(pending.suggestedQuantity()).isEqualByComparingTo("200");
        assertThat(pending.executable()).isFalse();
        assertThat(pending.nextAction())
                .isEqualTo("RECOMPUTE_USING_OPERATIONAL_DEMAND");
        assertThat(service.list(KITCHEN, LOCATION, "PENDING", 20))
                .extracting(SignalProposalView::proposalId)
                .containsExactly(fixture.proposalId());

        SignalProposalView dismissed = service.dismiss(
                fixture.proposalId(),
                KITCHEN,
                LOCATION,
                1,
                "operator@example.test",
                "Reviewed; recomputation does not support action.");
        assertThat(dismissed.status()).isEqualTo("DISMISSED");
        assertThat(dismissed.version()).isEqualTo(2);
        assertThat(dismissed.dismissedBy()).isEqualTo("operator@example.test");
        assertThatThrownBy(() -> service.dismiss(
                fixture.proposalId(),
                KITCHEN,
                LOCATION,
                1,
                "operator@example.test",
                "Replay"))
                .isInstanceOf(SignalProposalConflictException.class);

        assertThat(count("recommendations")).isEqualTo(recommendationsBefore);
        assertThat(count("stock_movements")).isEqualTo(movementsBefore);
        assertThat(jdbc.sql("""
                        SELECT COUNT(*) FROM analytics_outbox
                        WHERE event_type = 'GOVERNED_SIGNAL_PROPOSAL_DISMISSED'
                          AND aggregate_id = :id
                        """)
                .param("id", fixture.proposalId())
                .query(Long.class)
                .single()).isOne();
    }

    @Test
    void durablyRejectsMalformedOutOfScopeAndMessageIdCollisionInputs() {
        SignalProposalFixtures.Fixture valid = SignalProposalFixtures.shortage(
                KITCHEN, ingredientId);
        processor.process(
                valid.body(),
                "reused-message-id",
                PUBLISH_TIME,
                valid.attributes(),
                null);

        byte[] malformed = text(valid.body())
                .replace(
                        "\"directMutationAllowed\":false}",
                        "\"directMutationAllowed\":false,\"unknown\":true}")
                .getBytes(StandardCharsets.UTF_8);
        IngestResult collision = processor.process(
                malformed,
                "reused-message-id",
                PUBLISH_TIME,
                valid.attributes(),
                null);
        assertThat(collision.outcome()).isEqualTo(IngestOutcome.REJECTED);
        assertThat(collision.rejectionCode()).isEqualTo("MESSAGE_ID_COLLISION");

        SignalProposalFixtures.Fixture foreign = SignalProposalFixtures.shortage(
                "foreign-kitchen", ingredientId);
        IngestResult outOfScope = processor.process(
                foreign.body(),
                "foreign-message",
                PUBLISH_TIME,
                foreign.attributes(),
                null);
        assertThat(outOfScope.outcome()).isEqualTo(IngestOutcome.REJECTED);
        assertThat(outOfScope.rejectionCode()).isEqualTo("OUT_OF_SCOPE");

        assertThat(jdbc.sql("""
                        SELECT rejection_code
                        FROM signal_proposal_deliveries
                        WHERE outcome = 'REJECTED'
                        ORDER BY rejection_code
                        """)
                .query(String.class)
                .list()).containsExactly(
                        "MESSAGE_ID_COLLISION", "OUT_OF_SCOPE");
        assertThat(count("governed_signal_proposals")).isOne();
    }

    @Test
    void rejectsCanonicalUnitThatNoLongerMatchesOperationalIngredient() {
        SignalProposalFixtures.Fixture fixture = SignalProposalFixtures.shortage(
                KITCHEN, ingredientId);
        jdbc.sql("UPDATE ingredients SET base_unit = 'ml' WHERE id = :id")
                .param("id", ingredientId)
                .update();

        IngestResult result = processor.process(
                fixture.body(),
                "unit-mismatch-message",
                PUBLISH_TIME,
                fixture.attributes(),
                null);

        assertThat(result.outcome()).isEqualTo(IngestOutcome.REJECTED);
        assertThat(result.rejectionCode())
                .isEqualTo("CANONICAL_UNIT_MISMATCH");
        assertThat(count("governed_signal_proposals")).isZero();
    }

    private long count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table)
                .query(Long.class)
                .single();
    }

    private static String text(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }
}
