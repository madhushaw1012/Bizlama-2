package com.bizlama.api.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("memory")
@Transactional
class KitchenEventIntentRoutingIntegrationTest {

    private static final ZoneId KITCHEN_ZONE = ZoneId.of("Asia/Kolkata");

    @Autowired
    private KitchenEventIntentRouter router;

    @Autowired
    private KitchenEventProposalService proposals;

    @Autowired
    private KitchenEventController controller;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void explicitExpiryPurchasePreviewsThenWritesOneLotAndMovement() {
        LocalDate expiry = LocalDate.now(KITCHEN_ZONE).plusDays(5);
        String statement = "Bought 2 kg paneer, expires on " + expiry;
        List<ParsedKitchenEvent> events = router.route(statement);

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.intent())
                    .isEqualTo(KitchenEventIntent.INVENTORY_UPDATE);
            assertThat(event.type()).isEqualTo(KitchenEventType.PURCHASE);
            assertThat(event.itemId()).isEqualTo("paneer");
            assertThat(event.quantity()).isEqualByComparingTo("2000");
            assertThat(event.unit()).isEqualTo("g");
            assertThat(event.expiresAt()).isEqualTo(expiry);
        });

        var proposal = proposals.create(statement, events);
        String key = "purchase-" + UUID.randomUUID();
        var applied = proposals.confirm(
                proposal.id(), proposal.version(), key, "owner@test"
        );
        var replay = proposals.confirm(
                proposal.id(), proposal.version(), key, "owner@test"
        );

        assertThat(applied.idempotentReplay()).isFalse();
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(jdbc.sql("""
                        SELECT COUNT(*) FROM stock_lots
                        WHERE source = :source
                          AND ingredient_id = 'paneer'
                          AND expires_at = :expiry
                          AND expiry_provenance = 'OWNER_CONFIRMED'
                        """)
                .param("source", "kitchen-event:" + proposal.id())
                .param("expiry", expiry)
                .query(Long.class)
                .single()).isEqualTo(1L);
        assertThat(jdbc.sql("""
                        SELECT COUNT(*) FROM stock_movements
                        WHERE reference_id = :proposal
                          AND movement_type = 'PURCHASE'
                        """)
                .param("proposal", proposal.id())
                .query(Long.class)
                .single()).isEqualTo(1L);
    }

    @Test
    void multiDishOrderPreviewCreatesOneQueuedOrderWithBothLines() {
        String statement = "Order 2 paneer sandwiches and 1 chai";
        List<ParsedKitchenEvent> events = router.route(statement);

        assertThat(events).hasSize(2)
                .allSatisfy(event -> assertThat(event.intent())
                        .isEqualTo(KitchenEventIntent.ORDER_CAPTURE));
        assertThat(events)
                .extracting(ParsedKitchenEvent::itemId)
                .containsExactly("paneer-sandwich", "chai");
        assertThat(events)
                .extracting(ParsedKitchenEvent::quantity)
                .containsExactly(new BigDecimal("2"), new BigDecimal("1"));

        var proposal = proposals.create(statement, events);
        proposals.confirm(
                proposal.id(),
                proposal.version(),
                "order-" + UUID.randomUUID(),
                "owner@test"
        );

        String order = jdbc.sql("""
                        SELECT id FROM customer_orders
                        ORDER BY created_at DESC, id DESC
                        LIMIT 1
                        """)
                .query(String.class)
                .single();
        assertThat(jdbc.sql("""
                        SELECT status FROM customer_orders WHERE id = :order
                        """)
                .param("order", order)
                .query(String.class)
                .single()).isEqualTo("QUEUED");
        assertThat(jdbc.sql("""
                        SELECT dish_id, quantity FROM order_items
                        WHERE order_id = :order
                        ORDER BY line_number
                        """)
                .param("order", order)
                .query((rs, row) -> rs.getString("dish_id")
                        + ":" + rs.getInt("quantity"))
                .list()).containsExactly(
                        "paneer-sandwich:2",
                        "chai:1"
                );
    }

    @Test
    void dishFeedbackPreviewTargetsItsActiveRecipeAndWritesFeedback() {
        String statement = "Paneer sandwich was dry, rating 3";
        long before = jdbc.sql("""
                        SELECT COUNT(*) FROM feedback
                        WHERE recipe_id = 'paneer-sandwich-v1'
                        """)
                .query(Long.class)
                .single();
        List<ParsedKitchenEvent> events = router.route(statement);

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.intent())
                    .isEqualTo(KitchenEventIntent.FEEDBACK_CAPTURE);
            assertThat(event.type()).isEqualTo(KitchenEventType.FEEDBACK);
            assertThat(event.itemId()).isEqualTo("paneer-sandwich");
            assertThat(event.quantity()).isEqualByComparingTo("3");
            assertThat(event.note()).isEqualTo(statement);
        });

        var proposal = proposals.create(statement, events);
        proposals.confirm(
                proposal.id(),
                proposal.version(),
                "feedback-" + UUID.randomUUID(),
                "owner@test"
        );

        assertThat(jdbc.sql("""
                        SELECT COUNT(*) FROM feedback
                        WHERE recipe_id = 'paneer-sandwich-v1'
                        """)
                .query(Long.class)
                .single()).isEqualTo(before + 1);
        assertThat(jdbc.sql("""
                        SELECT COUNT(*) FROM feedback
                        WHERE recipe_id = 'paneer-sandwich-v1'
                          AND feedback_text = :text
                          AND rating = 3
                          AND source = 'kitchen-event'
                        """)
                .param("text", statement)
                .query(Long.class)
                .single()).isEqualTo(1L);
    }

    @Test
    void ambiguousTextReturnsUnknownClarificationWithoutProposalWrite() {
        long before = proposalCount();

        ParseKitchenEventResponse response = controller.parse(
                new ParseKitchenEventRequest("Something changed in the kitchen")
        );

        assertThat(response.intent()).isEqualTo(KitchenEventIntent.UNKNOWN);
        assertThat(response.requiresConfirmation()).isFalse();
        assertThat(response.proposalId()).isNull();
        assertThat(response.events()).isEmpty();
        assertThat(response.clarification()).isNotBlank();
        assertThat(proposalCount()).isEqualTo(before);
    }

    private long proposalCount() {
        return jdbc.sql("SELECT COUNT(*) FROM kitchen_event_proposals")
                .query(Long.class)
                .single();
    }
}
