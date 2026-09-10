package com.bizlama.api.orders;

import com.bizlama.api.config.JdbcTimestamp;
import com.bizlama.api.domain.Order;
import com.bizlama.api.outbox.OutboxEventDraft;
import com.bizlama.api.outbox.TransactionalOutboxService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Transactional authority for every persisted order-status transition. */
@Service
public class OrderTransitionService {

    private final JdbcClient jdbc;
    private final TransactionalOutboxService outbox;

    public OrderTransitionService(
            JdbcClient jdbc,
            TransactionalOutboxService outbox
    ) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    @Transactional
    public void transition(
            String kitchenId,
            String locationId,
            String orderId,
            Order.Status target,
            Instant changedAt,
            String actor,
            String note,
            String productionActionId
    ) {
        requireText(kitchenId, "Kitchen");
        requireText(locationId, "Location");
        requireText(orderId, "Order");
        Objects.requireNonNull(target, "Target status is required.");
        Objects.requireNonNull(changedAt, "Transition time is required.");
        requireText(actor, "Transition actor");
        requireText(note, "Transition note");

        Order.Status current = jdbc.sql("""
                        SELECT status
                        FROM customer_orders
                        WHERE id = :order
                          AND kitchen_id = :kitchen
                          AND location_id = :location
                        FOR UPDATE
                        """)
                .param("order", orderId)
                .param("kitchen", kitchenId)
                .param("location", locationId)
                .query(String.class)
                .optional()
                .map(Order.Status::valueOf)
                .orElseThrow(() -> new IllegalArgumentException("Order not found"));

        if (!allowed(current, target)) {
            throw new OrderTransitionConflictException(
                    "Order " + orderId + " cannot transition from "
                            + current + " to " + target + "."
            );
        }
        if (target == Order.Status.DONE
                && productionActionId != null
                && !productionActionId.isBlank()) {
            assertDoneEvidence(kitchenId, locationId, orderId, changedAt);
        }

        int changed = jdbc.sql("""
                        UPDATE customer_orders
                        SET status = :target,
                            updated_at = :changedAt
                        WHERE id = :order
                          AND kitchen_id = :kitchen
                          AND location_id = :location
                          AND status = :current
                        """)
                .param("target", target.name())
                .param("changedAt", JdbcTimestamp.utc(changedAt))
                .param("order", orderId)
                .param("kitchen", kitchenId)
                .param("location", locationId)
                .param("current", current.name())
                .update();
        if (changed != 1) {
            throw new OrderTransitionConflictException(
                    "Order status changed concurrently; reload before retrying."
            );
        }

        int audited = jdbc.sql("""
                        INSERT INTO order_status_history
                        (id, order_id, status, changed_by, note, changed_at)
                        SELECT :id, orders.id, :status, :actor, :note, :changedAt
                        FROM customer_orders orders
                        WHERE orders.id = :order
                          AND orders.kitchen_id = :kitchen
                          AND orders.location_id = :location
                        """)
                .param("id", UUID.randomUUID().toString())
                .param("order", orderId)
                .param("status", target.name())
                .param("actor", actor)
                .param("note", note)
                .param("changedAt", JdbcTimestamp.utc(changedAt))
                .param("kitchen", kitchenId)
                .param("location", locationId)
                .update();
        if (audited != 1) {
            throw new IllegalStateException(
                    "Order left this workspace before status history was recorded."
            );
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", orderId);
        payload.put("previousStatus", current.name());
        payload.put("status", target.name());
        payload.put("changedAt", changedAt);
        payload.put("changedBy", actor);
        payload.put("note", note);
        if (productionActionId != null && !productionActionId.isBlank()) {
            payload.put("productionActionId", productionActionId);
        }
        outbox.append(new OutboxEventDraft(
                null,
                "ORDER_STATUS_CHANGED",
                1,
                kitchenId,
                "order",
                orderId,
                changedAt,
                orderId,
                productionActionId,
                "order-status:" + orderId + ":" + target.name() + ":" + changedAt,
                payload,
                Map.of("component", "order-transition-service")
        ));
    }

    private void assertDoneEvidence(
            String kitchenId,
            String locationId,
            String orderId,
            Instant changedAt
    ) {
        ReadyEvidence evidence = jdbc.sql("""
                        SELECT COUNT(*) AS total_lines,
                               COALESCE(SUM(CASE
                                 WHEN prepared_quantity <> quantity
                                 THEN 1 ELSE 0 END), 0) AS incomplete_lines,
                               COALESCE(SUM(CASE
                                 WHEN covered_quantity <> quantity
                                 THEN 1 ELSE 0 END), 0) AS uncovered_lines
                        FROM (
                          SELECT item.line_number,
                                 item.quantity,
                                 item.prepared_quantity,
                                 COALESCE(SUM(CASE
                                   WHEN action.id IS NOT NULL
                                   THEN action_line.prepared_quantity
                                   ELSE 0 END), 0) AS covered_quantity
                          FROM order_items item
                          LEFT JOIN recommendation_action_order_lines action_line
                            ON action_line.order_id = item.order_id
                           AND action_line.line_number = item.line_number
                          LEFT JOIN recommendation_actions action
                            ON action.id = action_line.action_id
                           AND action.kitchen_id = :kitchen
                           AND action.location_id = :location
                           AND action.dish_id = item.dish_id
                           AND action.recipe_version_id = item.recipe_version_id
                           AND action.action_type = 'PRODUCTION_RUN'
                           AND action.action_status = 'COMPLETED'
                           AND action.created_at <= :changedAt
                          WHERE item.order_id = :order
                          GROUP BY item.line_number, item.quantity,
                                   item.prepared_quantity
                        ) evidence
                        """)
                .param("kitchen", kitchenId)
                .param("location", locationId)
                .param("changedAt", JdbcTimestamp.utc(changedAt.plusMillis(1)))
                .param("order", orderId)
                .query((rs, ignored) -> new ReadyEvidence(
                        rs.getLong("total_lines"),
                        rs.getLong("incomplete_lines"),
                        rs.getLong("uncovered_lines")
                ))
                .single();

        if (evidence.totalLines() == 0
                || evidence.incompleteLines() != 0
                || evidence.uncoveredLines() != 0) {
            throw new OrderTransitionConflictException(
                    "Order cannot become DONE until every line is fully prepared "
                            + "and backed by completed durable production actions "
                            + "(lines=" + evidence.totalLines()
                            + ", incomplete=" + evidence.incompleteLines()
                            + ", uncovered=" + evidence.uncoveredLines() + ")."
            );
        }
    }

    private boolean allowed(Order.Status current, Order.Status target) {
        return switch (current) {
            case QUEUED -> target == Order.Status.PREPARING
                    || target == Order.Status.CANCELLED;
            case PREPARING -> target == Order.Status.DONE
                    || target == Order.Status.CANCELLED;
            case DONE, CANCELLED -> false;
        };
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required.");
        }
    }

    private record ReadyEvidence(
            long totalLines,
            long incompleteLines,
            long uncoveredLines
    ) {
    }
}
