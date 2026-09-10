package com.bizlama.api.integrity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bizlama.api.domain.Order;
import com.bizlama.api.domain.StockMovement;
import com.bizlama.api.orders.OrderTransitionConflictException;
import com.bizlama.api.orders.OrderTransitionService;
import com.bizlama.api.stock.ExpiryProvenance;
import com.bizlama.api.stock.FutureDatedPurchaseException;
import com.bizlama.api.stock.InventoryAllocationService;
import com.bizlama.api.stock.InventoryPurchaseService;
import com.bizlama.api.store.OperationalRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ResponseStatus;

@SpringBootTest
@ActiveProfiles("memory")
@Transactional
class OrderInventoryBoundaryIntegrationTest {

    private static final String KITCHEN = "kitchen-default";
    private static final String LOCATION = "location-main";
    private static final String SEEDED_ORDER = "ORD-DEMO-001";
    private static final ZoneId KITCHEN_ZONE = ZoneId.of("Asia/Kolkata");

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private OperationalRepository repository;

    @Autowired
    private OrderTransitionService transitions;

    @Autowired
    private InventoryAllocationService allocations;

    @Autowired
    private InventoryPurchaseService purchases;

    @Test
    void queuedOrderCannotSkipPreparing() {
        long historyBefore = historyCount(SEEDED_ORDER);

        assertThatThrownBy(() -> repository.updateOrderStatus(
                SEEDED_ORDER,
                Order.Status.DONE,
                "integrity-test"
        )).isInstanceOf(OrderTransitionConflictException.class)
                .hasMessageContaining("cannot transition from QUEUED");

        assertThat(orderStatus(SEEDED_ORDER)).isEqualTo("QUEUED");
        assertThat(historyCount(SEEDED_ORDER)).isEqualTo(historyBefore);
        assertThat(OrderTransitionConflictException.class
                .getAnnotation(ResponseStatus.class).value())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void automatedDoneRequiresCompletedDurableProductionEvidenceForEveryLine() {
        jdbc.sql("""
                        UPDATE customer_orders
                        SET status = 'PREPARING'
                        WHERE id = :order
                        """)
                .param("order", SEEDED_ORDER)
                .update();
        jdbc.sql("""
                        UPDATE order_items
                        SET prepared_quantity = quantity
                        WHERE order_id = :order
                        """)
                .param("order", SEEDED_ORDER)
                .update();

        assertThatThrownBy(() -> transitions.transition(
                KITCHEN,
                LOCATION,
                SEEDED_ORDER,
                Order.Status.DONE,
                Instant.now(),
                "integrity-test",
                "Automated production completion.",
                "missing-production-action"
        )).isInstanceOf(OrderTransitionConflictException.class)
                .hasMessageContaining("completed durable production actions");

        assertThat(orderStatus(SEEDED_ORDER)).isEqualTo("PREPARING");
    }

    @ParameterizedTest
    @EnumSource(
            value = Order.Status.class,
            names = {"DONE", "CANCELLED"}
    )
    void terminalOrdersCannotBeReopened(Order.Status terminal) {
        jdbc.sql("""
                        UPDATE customer_orders
                        SET status = :status
                        WHERE id = :order
                        """)
                .param("status", terminal.name())
                .param("order", SEEDED_ORDER)
                .update();

        assertThatThrownBy(() -> repository.updateOrderStatus(
                SEEDED_ORDER,
                Order.Status.PREPARING,
                "integrity-test"
        )).isInstanceOf(OrderTransitionConflictException.class)
                .hasMessageContaining("cannot transition from " + terminal);

        assertThat(orderStatus(SEEDED_ORDER)).isEqualTo(terminal.name());
    }

    @Test
    void queuedPreparingDoneLifecyclePersistsAndRejectsDuplicate() {
        Order preparing = repository.updateOrderStatus(
                SEEDED_ORDER,
                Order.Status.PREPARING,
                "integrity-test"
        );
        assertThat(preparing.status()).isEqualTo(Order.Status.PREPARING);

        Order done = repository.updateOrderStatus(
                SEEDED_ORDER,
                Order.Status.DONE,
                "integrity-test"
        );
        assertThat(done.status()).isEqualTo(Order.Status.DONE);
        assertThat(orderStatus(SEEDED_ORDER)).isEqualTo("DONE");
        long historyAfterDone = historyCount(SEEDED_ORDER);

        assertThatThrownBy(() -> repository.updateOrderStatus(
                SEEDED_ORDER,
                Order.Status.DONE,
                "integrity-test"
        )).isInstanceOf(OrderTransitionConflictException.class)
                .hasMessageContaining("cannot transition from DONE");
        assertThat(historyCount(SEEDED_ORDER)).isEqualTo(historyAfterDone);
    }

    @Test
    void successfulTransitionPersistsActorNotePreviousStateAndOutboxAudit() {
        Order preparing = repository.updateOrderStatus(
                SEEDED_ORDER,
                Order.Status.PREPARING,
                "operator@example.test"
        );

        assertThat(preparing.status()).isEqualTo(Order.Status.PREPARING);
        assertThat(jdbc.sql("""
                        SELECT COUNT(*)
                        FROM order_status_history
                        WHERE order_id = :order
                          AND status = 'PREPARING'
                          AND changed_by = 'operator@example.test'
                          AND note = 'Advanced through the operational order API.'
                        """)
                .param("order", SEEDED_ORDER)
                .query(Long.class)
                .single()).isEqualTo(1L);
        assertThat(jdbc.sql("""
                        SELECT COUNT(*)
                        FROM analytics_outbox
                        WHERE aggregate_type = 'order'
                          AND aggregate_id = :order
                          AND event_type = 'ORDER_STATUS_CHANGED'
                          AND payload_json LIKE '%"previousStatus":"QUEUED"%'
                          AND payload_json LIKE '%operator@example.test%'
                        """)
                .param("order", SEEDED_ORDER)
                .query(Long.class)
                .single()).isEqualTo(1L);
    }

    @Test
    void fefoNeverAllocatesALotPurchasedAfterTheActionDate() {
        String ingredient = insertIngredient("future-fefo");
        Instant actionAt = Instant.now();
        LocalDate actionDate = actionAt.atZone(KITCHEN_ZONE).toLocalDate();
        String futureLot = insertLot(
                ingredient,
                "future",
                "100",
                actionDate.plusDays(1),
                actionDate.plusDays(2)
        );
        String currentLot = insertLot(
                ingredient,
                "current",
                "100",
                actionDate.minusDays(1),
                actionDate.plusDays(3)
        );

        var result = allocations.allocate(
                KITCHEN,
                LOCATION,
                ingredient,
                new BigDecimal("50"),
                "g",
                actionAt,
                StockMovement.MovementType.WASTE,
                "future-boundary-test",
                UUID.randomUUID().toString()
        );

        assertThat(result.lots())
                .extracting(InventoryAllocationService.LotAllocation::lotId)
                .containsExactly(currentLot);
        assertThat(lotQuantity(futureLot)).isEqualByComparingTo("100");
        assertThat(lotQuantity(currentLot)).isEqualByComparingTo("50");
    }

    @Test
    void futureDatedPurchaseIsRejectedBeforeLotMovementOrOutboxWrites() {
        String ingredient = insertIngredient("future-input");
        String lotId = "future-input-lot-" + UUID.randomUUID();
        Instant occurredAt = Instant.now();
        LocalDate occurrenceDate = occurredAt.atZone(KITCHEN_ZONE).toLocalDate();

        assertThatThrownBy(() -> purchases.add(
                new InventoryPurchaseService.Purchase(
                        lotId,
                        KITCHEN,
                        LOCATION,
                        ingredient,
                        new BigDecimal("25"),
                        "g",
                        new BigDecimal("25"),
                        "g",
                        occurrenceDate.plusDays(1),
                        occurrenceDate.plusDays(5),
                        ExpiryProvenance.OWNER_CONFIRMED,
                        "future-input-test",
                        "future-input-test",
                        lotId,
                        null,
                        null,
                        occurredAt
                )
        )).isInstanceOf(FutureDatedPurchaseException.class)
                .hasMessageContaining("cannot be after the kitchen-local occurrence date");

        assertThat(FutureDatedPurchaseException.class
                .getAnnotation(ResponseStatus.class).value())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(stockLotCount(lotId)).isZero();
        assertThat(jdbc.sql("""
                        SELECT COUNT(*)
                        FROM stock_movements
                        WHERE reference_id = :reference
                        """)
                .param("reference", lotId)
                .query(Long.class)
                .single()).isZero();
        assertThat(jdbc.sql("""
                        SELECT COUNT(*)
                        FROM analytics_outbox
                        WHERE aggregate_id = :lot
                        """)
                .param("lot", lotId)
                .query(Long.class)
                .single()).isZero();
    }

    private String insertIngredient(String label) {
        String id = label + "-" + UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO ingredients
                        (id, name, base_unit, active, kitchen_id)
                        VALUES (:id, :name, 'g', TRUE, :kitchen)
                        """)
                .param("id", id)
                .param("name", "Boundary " + label)
                .param("kitchen", KITCHEN)
                .update();
        return id;
    }

    private String insertLot(
            String ingredient,
            String label,
            String quantity,
            LocalDate purchasedAt,
            LocalDate expiresAt
    ) {
        String id = label + "-" + UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO stock_lots
                        (id, ingredient_id, quantity_remaining, unit,
                         purchased_at, expires_at, source, kitchen_id, location_id,
                         status, version, expiry_provenance,
                         source_quantity, source_unit)
                        VALUES
                        (:id, :ingredient, :quantity, 'g',
                         :purchasedAt, :expiresAt, 'boundary-test', :kitchen, :location,
                         'AVAILABLE', 1, 'OWNER_CONFIRMED', :quantity, 'g')
                        """)
                .param("id", id)
                .param("ingredient", ingredient)
                .param("quantity", new BigDecimal(quantity))
                .param("purchasedAt", purchasedAt)
                .param("expiresAt", expiresAt)
                .param("kitchen", KITCHEN)
                .param("location", LOCATION)
                .update();
        return id;
    }

    private String orderStatus(String orderId) {
        return jdbc.sql("SELECT status FROM customer_orders WHERE id = :id")
                .param("id", orderId)
                .query(String.class)
                .single();
    }

    private long historyCount(String orderId) {
        return jdbc.sql("""
                        SELECT COUNT(*)
                        FROM order_status_history
                        WHERE order_id = :order
                        """)
                .param("order", orderId)
                .query(Long.class)
                .single();
    }

    private BigDecimal lotQuantity(String lotId) {
        return jdbc.sql("""
                        SELECT quantity_remaining
                        FROM stock_lots
                        WHERE id = :id
                        """)
                .param("id", lotId)
                .query(BigDecimal.class)
                .single();
    }

    private long stockLotCount(String id) {
        return jdbc.sql("SELECT COUNT(*) FROM stock_lots WHERE id = :id")
                .param("id", id)
                .query(Long.class)
                .single();
    }
}
