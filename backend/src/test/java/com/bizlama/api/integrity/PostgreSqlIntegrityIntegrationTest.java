package com.bizlama.api.integrity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bizlama.api.domain.OrderSummary;
import com.bizlama.api.domain.StockMovement;
import com.bizlama.api.experiment.ExperimentService;
import com.bizlama.api.events.ConfirmKitchenEventRequest;
import com.bizlama.api.events.KitchenEventProposalService;
import com.bizlama.api.events.KitchenEventType;
import com.bizlama.api.events.ParsedKitchenEvent;
import com.bizlama.api.explanations.ExplanationPersistence;
import com.bizlama.api.explanations.ExplanationPersistence.CacheKey;
import com.bizlama.api.explanations.ExplanationProvider.Metadata;
import com.bizlama.api.explanations.ExplanationProvider.Snapshot;
import com.bizlama.api.feedback.FeedbackService;
import com.bizlama.api.receipts.ReceiptReviewService;
import com.bizlama.api.receipts.ReceiptView;
import com.bizlama.api.stock.ExpiryProvenance;
import com.bizlama.api.stock.InsufficientStockException;
import com.bizlama.api.stock.InventoryAllocationService;
import com.bizlama.api.store.OperationalRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "bizlama.outbox.dispatch-enabled=false",
            "bizlama.receipts.ai.enabled=false",
            "bizlama.receipts.storage-mode=local",
            "bizlama.receipts.local-directory=/tmp/bizlama-receipt-tests"
        }
)
@Testcontainers
class PostgreSqlIntegrityIntegrationTest {

    private static final String KITCHEN = "kitchen-default";
    private static final String LOCATION = "location-main";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("bizlama_integrity")
                    .withUsername("bizlama")
                    .withPassword("bizlama");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name",
                () -> "org.postgresql.Driver");
    }

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private InventoryAllocationService allocations;

    @Autowired
    private OperationalRepository repository;
    @Autowired
    private FeedbackService feedback;

    @Autowired
    private ExperimentService experiments;


    @Autowired
    private ReceiptReviewService receipts;

    @Autowired
    private KitchenEventProposalService proposals;

    @Autowired
    private ExplanationPersistence explanations;

    @Test
    void cleanPostgreSqlStartupAppliesV1ThroughV21AndInstallsIntegrityConstraints() {
        flyway.validate();

        assertThat(flyway.info().current().getVersion().getVersion())
                .isEqualTo("21");
        assertThat(Arrays.stream(flyway.info().applied())
                .map(info -> info.getVersion().getVersion()))
                .containsExactly(
                        "1", "2", "3", "4", "5", "6",
                        "7", "8", "9", "10", "11", "12", "13", "14",
                        "15", "16", "17", "18", "19", "20", "21");

        List<String> constraints = jdbc.sql("""
                        SELECT conname
                        FROM pg_constraint
                        WHERE conname IN (
                            'ck_stock_lots_quantity_remaining_nonnegative',
                            'ck_stock_lots_expiry_evidence',
                            'fk_stock_movements_lot_scope',
                            'fk_order_items_recipe_version_same_dish',
                            'ck_receipt_items_review_audit',
                            'ck_kitchen_event_proposals_applied_state',
                            'ck_governed_signal_proposals_non_executable',
                            'fk_governed_signal_proposals_location_scope',
                            'fk_feedback_recipe_same_kitchen'
                        )
                        ORDER BY conname
                        """)
                .query(String.class)
                .list();
        assertThat(constraints).containsExactly(
                "ck_governed_signal_proposals_non_executable",
                "ck_kitchen_event_proposals_applied_state",
                "ck_receipt_items_review_audit",
                "ck_stock_lots_expiry_evidence",
                "ck_stock_lots_quantity_remaining_nonnegative",
                "fk_feedback_recipe_same_kitchen",
                "fk_governed_signal_proposals_location_scope",
                "fk_order_items_recipe_version_same_dish",
                "fk_stock_movements_lot_scope"
        );

        assertThat(jdbc.sql("""
                        SELECT indexname
                        FROM pg_indexes
                        WHERE schemaname = 'public'
                          AND indexname = 'uq_recipe_versions_one_active_per_dish'
                        """)
                .query(String.class)
                .single()).isEqualTo("uq_recipe_versions_one_active_per_dish");

        String ingredient = insertIngredient("constraint", "g");
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO stock_lots
                        (id, ingredient_id, quantity_remaining, unit,
                         purchased_at, expires_at, source, kitchen_id, location_id,
                         status, version, expiry_provenance, source_quantity, source_unit)
                        VALUES
                        (:id, :ingredient, -1, 'g',
                         CURRENT_DATE, CURRENT_DATE + 1, 'test', :kitchen, :location,
                         'AVAILABLE', 1, 'OWNER_CONFIRMED', 1, 'g')
                        """)
                .param("id", id("invalid-lot"))
                .param("ingredient", ingredient)
                .param("kitchen", KITCHEN)
                .param("location", LOCATION)
                .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_stock_lots_quantity_remaining_nonnegative");
    }

    @Test
    void persistedFeedbackCreatesAProposalWithoutChangingThePermanentRecipe() {
        var proposed = experiments.getExperiment("demo-mango-lassi");

        assertThat(proposed.dish()).isEqualTo("Mango Lassi");
        assertThat(proposed.theme()).isEqualTo("Too sweet");
        assertThat(proposed.themeCount()).isEqualTo(3);
        assertThat(proposed.feedbackCount()).isEqualTo(5);
        assertThat(proposed.status().name()).isEqualTo("PROPOSED");
        assertThat(jdbc.sql("""
                        SELECT active_recipe_version_id
                        FROM dishes WHERE id = 'demo-mango-lassi'
                        """)
                .query(String.class)
                .single()).isEqualTo("demo-mango-lassi-v1");
    }

    @Test
    void feedbackRollsBackWhenItsRequiredActivityWriteFails() {
        String dish = id("feedback-rollback-dish");
        String recipe = id("feedback-rollback-recipe");
        Instant createdAt = Instant.now().minusSeconds(60);
        jdbc.sql("""
                        INSERT INTO dishes
                        (id, name, price, active, created_at, kitchen_id)
                        VALUES
                        (:id, 'Feedback rollback dish', 10.00, TRUE,
                         :createdAt, :kitchen)
                        """)
                .param("id", dish)
                .param("createdAt", postgresTime(createdAt))
                .param("kitchen", KITCHEN)
                .update();
        jdbc.sql("""
                        INSERT INTO recipe_versions
                        (id, dish_id, version_number, change_reason, active,
                         active_dish_guard, created_at, effective_at,
                         yield_quantity, yield_unit, kitchen_id)
                        VALUES
                        (:id, :dish, 1, 'Feedback rollback fixture', TRUE,
                         :dish, :createdAt, :createdAt, 1.000, 'each', :kitchen)
                        """)
                .param("id", recipe)
                .param("dish", dish)
                .param("createdAt", postgresTime(createdAt))
                .param("kitchen", KITCHEN)
                .update();
        jdbc.sql("""
                        UPDATE dishes
                        SET active_recipe_version_id = :recipe
                        WHERE id = :dish AND kitchen_id = :kitchen
                        """)
                .param("recipe", recipe)
                .param("dish", dish)
                .param("kitchen", KITCHEN)
                .update();

        jdbc.sql("""
                        ALTER TABLE activity_events
                        DROP CONSTRAINT IF EXISTS ck_test_feedback_activity_failure
                        """).update();
        jdbc.sql("""
                        ALTER TABLE activity_events
                        ADD CONSTRAINT ck_test_feedback_activity_failure
                        CHECK (event_type <> 'Feedback')
                        """).update();
        try {
            assertThatThrownBy(() ->
                    feedback.capture(recipe, "Too salty.", 3, "rollback-test"))
                    .isInstanceOf(DataAccessException.class);

            assertThat(jdbc.sql("""
                            SELECT COUNT(*) FROM feedback
                            WHERE recipe_id = :recipe
                            """)
                    .param("recipe", recipe)
                    .query(Long.class)
                    .single()).isZero();
        } finally {
            jdbc.sql("""
                            ALTER TABLE activity_events
                            DROP CONSTRAINT IF EXISTS ck_test_feedback_activity_failure
                            """).update();
        }
    }
    @Test
    void concurrentRecipeActivationLeavesExactlyOneActiveVersion() throws Exception {
        String dish = id("recipe-lock-dish");
        String initial = id("recipe-lock-v1");
        String firstCandidate = id("recipe-lock-v2");
        String secondCandidate = id("recipe-lock-v3");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        jdbc.sql("""
                        INSERT INTO dishes
                        (id, name, price, active, created_at, kitchen_id)
                        VALUES (:id, 'Concurrent recipe dish', 10.00, TRUE,
                                :createdAt, :kitchen)
                        """)
                .param("id", dish)
                .param("createdAt", postgresTime(now.minusSeconds(60)))
                .param("kitchen", KITCHEN)
                .update();
        jdbc.sql("""
                        INSERT INTO recipe_versions
                        (id, dish_id, version_number, change_reason, active,
                         active_dish_guard, created_at, yield_quantity, yield_unit,
                         effective_at, kitchen_id)
                        VALUES
                        (:initial, :dish, 1, 'Initial active recipe', TRUE, :dish,
                         :createdAt, 1.000, 'each', :effectiveAt, :kitchen),
                        (:first, :dish, 2, 'First proposal', FALSE, NULL,
                         :createdAt, 1.000, 'each', NULL, :kitchen),
                        (:second, :dish, 3, 'Second proposal', FALSE, NULL,
                         :createdAt, 1.000, 'each', NULL, :kitchen)
                        """)
                .param("initial", initial)
                .param("first", firstCandidate)
                .param("second", secondCandidate)
                .param("dish", dish)
                .param("createdAt", postgresTime(now.minusSeconds(30)))
                .param("effectiveAt", postgresTime(now.minusSeconds(30)))
                .param("kitchen", KITCHEN)
                .update();
        jdbc.sql("""
                        UPDATE dishes
                        SET active_recipe_version_id = :recipe
                        WHERE id = :dish
                        """)
                .param("recipe", initial)
                .param("dish", dish)
                .update();

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = pool.submit(() -> {
                start.await();
                return repository.activateRecipe(
                        firstCandidate,
                        "concurrent-owner-a"
                ).id();
            });
            Future<String> second = pool.submit(() -> {
                start.await();
                return repository.activateRecipe(
                        secondCandidate,
                        "concurrent-owner-b"
                ).id();
            });
            start.countDown();

            assertThat(List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder(firstCandidate, secondCandidate);
        } finally {
            pool.shutdownNow();
        }

        List<String> active = jdbc.sql("""
                        SELECT id
                        FROM recipe_versions
                        WHERE dish_id = :dish
                          AND active = TRUE
                        """)
                .param("dish", dish)
                .query(String.class)
                .list();
        assertThat(active).hasSize(1);
        assertThat(jdbc.sql("""
                        SELECT active_recipe_version_id
                        FROM dishes
                        WHERE id = :dish
                        """)
                .param("dish", dish)
                .query(String.class)
                .single()).isEqualTo(active.getFirst());

        String inactive = active.getFirst().equals(firstCandidate)
                ? secondCandidate
                : firstCandidate;
        assertThatThrownBy(() -> jdbc.sql("""
                        UPDATE recipe_versions
                        SET active = TRUE,
                            active_dish_guard = :dish,
                            effective_at = :effectiveAt,
                            superseded_at = NULL,
                            superseded_by_version_id = NULL
                        WHERE id = :recipe
                        """)
                .param("effectiveAt", postgresTime(Instant.now()))
                .param("dish", dish)
                .param("recipe", inactive)
                .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uq_recipe_versions_one_active_per_dish");
    }

    @Test
    void explanationCacheUsesPostgreSqlConflictKeyIdempotently() {
        String ingredient = insertIngredient("explanation", "kg");
        String calculation = id("explanation-calculation");
        String recommendation = id("explanation-recommendation");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        jdbc.sql("""
                        INSERT INTO demand_calculation_snapshots
                        (id, kitchen_id, location_id, horizon_start, horizon_end,
                         as_of, schema_version, calculation_method, payload_json,
                         payload_sha256, calculated_at, created_by)
                        VALUES
                        (:id, :kitchen, :location, :start, :end,
                         :asOf, 1, 'DETERMINISTIC_DEMAND_V1', '{}',
                         :hash, :calculatedAt, 'postgres-test')
                        """)
                .param("id", calculation)
                .param("kitchen", KITCHEN)
                .param("location", LOCATION)
                .param("start", postgresTime(now.minusSeconds(60)))
                .param("end", postgresTime(now.plusSeconds(3600)))
                .param("asOf", postgresTime(now))
                .param("hash", "0".repeat(64))
                .param("calculatedAt", postgresTime(now))
                .update();
        jdbc.sql("""
                        INSERT INTO recommendations
                        (id, kitchen_id, location_id, recommendation_type,
                         ingredient_id, proposed_quantity, unit, calculation_id,
                         calculation_schema_version, confidence,
                         confidence_components_json, reason_code, risk_tier,
                         status, version, expires_at, created_at, created_by,
                         updated_at)
                        VALUES
                        (:id, :kitchen, :location, 'PURCHASE',
                         :ingredient, 2.500, 'kg', :calculation,
                         1, 0.9000, '{}', 'SHORTAGE', 'LOW',
                         'PENDING', 1, :expiresAt, :createdAt, 'postgres-test',
                         :updatedAt)
                        """)
                .param("id", recommendation)
                .param("kitchen", KITCHEN)
                .param("location", LOCATION)
                .param("ingredient", ingredient)
                .param("calculation", calculation)
                .param("expiresAt", postgresTime(now.plusSeconds(1800)))
                .param("createdAt", postgresTime(now))
                .param("updatedAt", postgresTime(now))
                .update();

        Metadata metadata = new Metadata(
                "vertex-ai", "gemini-test", "prompt-v1");
        CacheKey key = new CacheKey("a".repeat(64), metadata);
        Snapshot snapshot = new Snapshot(
                1,
                recommendation,
                1,
                calculation,
                "PURCHASE",
                "PENDING",
                "LOW",
                "SHORTAGE",
                new BigDecimal("2.500"),
                "kg",
                new BigDecimal("0.9000"),
                ingredient,
                "Ingredient",
                "DETERMINISTIC_DEMAND_V1",
                now.minusSeconds(60),
                now.plusSeconds(3600),
                now,
                Map.of("shortage", new BigDecimal("2.500"))
        );
        explanations.cache(
                key,
                snapshot,
                """
                        {"summary":"First","drivers":["One"],"caveats":[]}
                        """,
                now
        );
        explanations.cache(
                key,
                snapshot,
                """
                        {"summary":"Second","drivers":["Two"],"caveats":[]}
                        """,
                now.plusSeconds(1)
        );

        assertThat(explanations.find(key)).get().satisfies(entry -> {
            assertThat(entry.responseJson())
                    .contains("First")
                    .doesNotContain("Second");
            assertThat(entry.createdAt()).isEqualTo(now);
        });
        assertThat(jdbc.sql("""
                        SELECT COUNT(*)
                        FROM recommendation_explanation_cache
                        WHERE input_hash = :hash
                        """)
                .param("hash", key.inputHash())
                .query(Long.class)
                .single()).isEqualTo(1L);
    }

    @Test
    void allocationUsesFefoAndExcludesExpiredAndQuarantinedLots() {
        String ingredient = insertIngredient("fefo", "g");
        LocalDate today = kitchenDate(Instant.now());
        String expired = insertLot(
                ingredient, "expired", "500", today.minusDays(10),
                today.minusDays(1), "AVAILABLE");
        String quarantined = insertLot(
                ingredient, "quarantined", "500", today.minusDays(1),
                today.plusDays(1), "QUARANTINED");
        String earliest = insertLot(
                ingredient, "early", "100", today.minusDays(1),
                today.plusDays(2), "AVAILABLE");
        String later = insertLot(
                ingredient, "late", "100", today.minusDays(1),
                today.plusDays(5), "AVAILABLE");

        var result = allocations.allocate(
                KITCHEN,
                LOCATION,
                ingredient,
                new BigDecimal("150"),
                "g",
                Instant.now(),
                StockMovement.MovementType.WASTE,
                "integrity-test",
                id("fefo-ref")
        );

        assertThat(result.lots())
                .extracting(InventoryAllocationService.LotAllocation::lotId)
                .containsExactly(earliest, later);
        assertThat(quantity(earliest)).isEqualByComparingTo("0");
        assertThat(status(earliest)).isEqualTo("DEPLETED");
        assertThat(quantity(later)).isEqualByComparingTo("50");
        assertThat(quantity(expired)).isEqualByComparingTo("500");
        assertThat(quantity(quarantined)).isEqualByComparingTo("500");
    }

    @Test
    void insufficientAllocationRollsBackEveryLotMovementAndOutboxWrite() {
        String ingredient = insertIngredient("rollback", "g");
        LocalDate today = kitchenDate(Instant.now());
        String first = insertLot(
                ingredient, "rollback-a", "40", today.minusDays(1),
                today.plusDays(2), "AVAILABLE");
        String second = insertLot(
                ingredient, "rollback-b", "30", today.minusDays(1),
                today.plusDays(3), "AVAILABLE");
        String reference = id("insufficient-ref");
        long movementsBefore = movementCount(reference);
        long outboxBefore = outboxCount(reference);

        assertThatThrownBy(() -> allocations.allocate(
                KITCHEN,
                LOCATION,
                ingredient,
                new BigDecimal("80"),
                "g",
                Instant.now(),
                StockMovement.MovementType.PRODUCTION_CONSUMPTION,
                "integrity-test",
                reference
        ))
                .isInstanceOf(InsufficientStockException.class)
                .satisfies(error -> {
                    InsufficientStockException shortage =
                            (InsufficientStockException) error;
                    assertThat(shortage.requested()).isEqualByComparingTo("80");
                    assertThat(shortage.available()).isEqualByComparingTo("70");
                    assertThat(shortage.shortfall()).isEqualByComparingTo("10");
                });

        assertThat(quantity(first)).isEqualByComparingTo("40");
        assertThat(quantity(second)).isEqualByComparingTo("30");
        assertThat(movementCount(reference)).isEqualTo(movementsBefore);
        assertThat(outboxCount(reference)).isEqualTo(outboxBefore);
    }

    @Test
    void simultaneousConsumptionCannotOversellOneLot() throws Exception {
        String ingredient = insertIngredient("concurrent", "g");
        LocalDate today = kitchenDate(Instant.now());
        String lot = insertLot(
                ingredient, "concurrent", "100", today.minusDays(1),
                today.plusDays(3), "AVAILABLE");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = pool.submit(() -> allocateAfterSignal(
                    start, ingredient, id("concurrent-ref-a")));
            Future<Boolean> second = pool.submit(() -> allocateAfterSignal(
                    start, ingredient, id("concurrent-ref-b")));
            start.countDown();

            assertThat(List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder(true, false);
        } finally {
            pool.shutdownNow();
        }

        assertThat(quantity(lot)).isEqualByComparingTo("20");
        assertThat(jdbc.sql("""
                        SELECT COUNT(*)
                        FROM stock_movements
                        WHERE stock_lot_id = :lot
                          AND movement_type = 'WASTE'
                        """)
                .param("lot", lot)
                .query(Long.class)
                .single()).isEqualTo(1L);
        assertThat(jdbc.sql("""
                        SELECT COALESCE(SUM(quantity_change), 0)
                        FROM stock_movements
                        WHERE stock_lot_id = :lot
                          AND movement_type = 'WASTE'
                        """)
                .param("lot", lot)
                .query(BigDecimal.class)
                .single()).isEqualByComparingTo("-80");
    }

    @Test
    void revenueCountsCompletedOrdersAndNeverCancelledOrders() {
        OrderSummary before = repository.orderSummary();
        Instant now = Instant.now();
        insertOrder(id("done"), new BigDecimal("101.25"), "DONE", now);
        insertOrder(id("cancelled"), new BigDecimal("999.99"), "CANCELLED", now);

        OrderSummary after = repository.orderSummary();
        assertThat(after.completedToday() - before.completedToday()).isEqualTo(1);
        assertThat(after.revenueToday().subtract(before.revenueToday()))
                .isEqualByComparingTo("101.25");
    }

    @Test
    void receiptConfirmationIsAtomicIdempotentAndUsesOnlyPersistedReviewedLines() {
        String receipt = id("receipt-success");
        String idempotencyKey = idForReceiptKey(receipt);
        String line = "line-1";
        insertReviewedReceipt(receipt, 1, List.of(
                new ReviewedLine(line, "paneer", "250", "g")
        ));

        assertThat(Arrays.stream(
                ReceiptReviewService.ConfirmCommand.class.getRecordComponents())
                .map(component -> component.getName()))
                .containsExactly("expectedVersion", "idempotencyKey");

        ReceiptView confirmed = receipts.confirm(
                receipt,
                new ReceiptReviewService.ConfirmCommand(1, idempotencyKey),
                "owner@test"
        );
        assertThat(confirmed.status().name()).isEqualTo("CONFIRMED");
        assertThat(confirmed.version()).isEqualTo(2);
        assertThat(jdbc.sql("""
                        SELECT quantity_remaining
                        FROM stock_lots
                        WHERE receipt_id = :receipt
                          AND receipt_item_id = :line
                        """)
                .param("receipt", receipt)
                .param("line", line)
                .query(BigDecimal.class)
                .single()).isEqualByComparingTo("250");

        ReceiptView replay = receipts.confirm(
                receipt,
                new ReceiptReviewService.ConfirmCommand(1, idempotencyKey),
                "owner@test"
        );
        assertThat(replay.version()).isEqualTo(2);
        assertThat(jdbc.sql("""
                        SELECT COUNT(*)
                        FROM stock_lots
                        WHERE receipt_id = :receipt
                        """)
                .param("receipt", receipt)
                .query(Long.class)
                .single()).isEqualTo(1L);

        assertThatThrownBy(() -> receipts.confirm(
                receipt,
                new ReceiptReviewService.ConfirmCommand(2, id("different-key")),
                "owner@test"
        ))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(
                        ((ResponseStatusException) error).getStatusCode().value())
                        .isEqualTo(409));
    }

    @Test
    void receiptFailureRollsBackPriorLinesAndStaleVersionCannotConfirm() {
        String receipt = id("receipt-rollback");
        String firstLine = "line-a";
        String collidingLine = "line-b";
        insertReviewedReceipt(receipt, 1, List.of(
                new ReviewedLine(firstLine, "paneer", "25", "g"),
                new ReviewedLine(collidingLine, "paneer", "30", "g")
        ));
        String collidingLotId = "receipt-" + receipt + "-" + collidingLine;
        LocalDate today = kitchenDate(Instant.now());
        insertLotWithId(
                collidingLotId,
                "paneer",
                "1",
                today,
                today.plusDays(1),
                "AVAILABLE"
        );
        long activityBefore = receiptActivityCount(receipt);

        assertThatThrownBy(() -> receipts.confirm(
                receipt,
                new ReceiptReviewService.ConfirmCommand(
                        1, idForReceiptKey(receipt)),
                "owner@test"
        )).isInstanceOf(DataAccessException.class);

        assertThat(receiptStatus(receipt)).isEqualTo("REVIEW_REQUIRED");
        assertThat(jdbc.sql("""
                        SELECT COUNT(*)
                        FROM stock_lots
                        WHERE receipt_id = :receipt
                        """)
                .param("receipt", receipt)
                .query(Long.class)
                .single()).isZero();
        assertThat(receiptActivityCount(receipt)).isEqualTo(activityBefore);

        String stale = id("receipt-stale");
        insertReviewedReceipt(stale, 2, List.of(
                new ReviewedLine("line-stale", "paneer", "10", "g")
        ));
        assertThatThrownBy(() -> receipts.confirm(
                stale,
                new ReceiptReviewService.ConfirmCommand(
                        1, idForReceiptKey(stale)),
                "owner@test"
        ))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(
                        ((ResponseStatusException) error).getStatusCode().value())
                        .isEqualTo(409));
        assertThat(jdbc.sql("""
                        SELECT COUNT(*)
                        FROM stock_lots
                        WHERE receipt_id = :receipt
                        """)
                .param("receipt", stale)
                .query(Long.class)
                .single()).isZero();
    }

    @Test
    void kitchenEventConfirmationReloadsHighRiskPersistedProposalAndRejectsStaleVersion() {
        String ingredient = insertIngredient("event", "g");
        LocalDate today = kitchenDate(Instant.now());
        String lot = insertLot(
                ingredient, "event", "100", today.minusDays(1),
                today.plusDays(3), "AVAILABLE");
        ParsedKitchenEvent persisted = new ParsedKitchenEvent(
                KitchenEventType.WASTE,
                "Event ingredient",
                ingredient,
                new BigDecimal("10"),
                "g",
                0.97,
                "Waste 10g",
                "Matched test ingredient"
        );
        var proposal = proposals.create("wasted ten grams", List.of(persisted));
        ParsedKitchenEvent clientTamperingAttempt = new ParsedKitchenEvent(
                KitchenEventType.WASTE,
                "Event ingredient",
                ingredient,
                new BigDecimal("99"),
                "g",
                1.0,
                "Waste 99g",
                "client mutation"
        );

        assertThat(proposal.riskTier()).isEqualTo("HIGH");
        assertThat(proposal.status()).isEqualTo("PENDING");
        assertThat(clientTamperingAttempt.quantity()).isEqualByComparingTo("99");
        assertThat(Arrays.stream(
                ConfirmKitchenEventRequest.class.getRecordComponents())
                .map(component -> component.getName()))
                .containsExactly("proposalId", "expectedVersion", "idempotencyKey");

        var result = proposals.confirm(
                proposal.id(),
                proposal.version(),
                id("event-key"),
                "owner@test"
        );
        assertThat(result.version()).isEqualTo(2);
        assertThat(result.idempotentReplay()).isFalse();
        assertThat(quantity(lot)).isEqualByComparingTo("90");

        var stale = proposals.create("wasted ten more grams", List.of(persisted));
        assertThatThrownBy(() -> proposals.confirm(
                stale.id(),
                stale.version() + 1,
                id("stale-event-key"),
                "owner@test"
        ))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(
                        ((ResponseStatusException) error).getStatusCode().value())
                        .isEqualTo(409));
        assertThat(quantity(lot)).isEqualByComparingTo("90");
    }

    private boolean allocateAfterSignal(
            CountDownLatch start,
            String ingredient,
            String reference
    ) throws InterruptedException {
        start.await();
        try {
            allocations.allocate(
                    KITCHEN,
                    LOCATION,
                    ingredient,
                    new BigDecimal("80"),
                    "g",
                    Instant.now(),
                    StockMovement.MovementType.WASTE,
                    "concurrency-test",
                    reference
            );
            return true;
        } catch (InsufficientStockException expected) {
            return false;
        }
    }

    private String insertIngredient(String label, String unit) {
        String ingredient = id("ingredient-" + label);
        jdbc.sql("""
                        INSERT INTO ingredients
                        (id, name, base_unit, active, kitchen_id)
                        VALUES (:id, :name, :unit, TRUE, :kitchen)
                        """)
                .param("id", ingredient)
                .param("name", "Integrity " + label)
                .param("unit", unit)
                .param("kitchen", KITCHEN)
                .update();
        return ingredient;
    }

    private String insertLot(
            String ingredient,
            String label,
            String quantity,
            LocalDate purchasedAt,
            LocalDate expiresAt,
            String status
    ) {
        String lot = id("lot-" + label);
        insertLotWithId(
                lot, ingredient, quantity, purchasedAt, expiresAt, status);
        return lot;
    }

    private void insertLotWithId(
            String lot,
            String ingredient,
            String quantity,
            LocalDate purchasedAt,
            LocalDate expiresAt,
            String status
    ) {
        jdbc.sql("""
                        INSERT INTO stock_lots
                        (id, ingredient_id, quantity_remaining, unit,
                         purchased_at, expires_at, source, kitchen_id, location_id,
                         status, version, expiry_provenance, source_quantity, source_unit)
                        VALUES
                        (:id, :ingredient, :quantity, 'g',
                         :purchasedAt, :expiresAt, 'integrity-test', :kitchen, :location,
                         :status, 1, 'OWNER_CONFIRMED', :quantity, 'g')
                        """)
                .param("id", lot)
                .param("ingredient", ingredient)
                .param("quantity", new BigDecimal(quantity))
                .param("purchasedAt", purchasedAt)
                .param("expiresAt", expiresAt)
                .param("kitchen", KITCHEN)
                .param("location", LOCATION)
                .param("status", status)
                .update();
    }

    private void insertOrder(
            String order,
            BigDecimal total,
            String status,
            Instant now
    ) {
        Instant created = now.minus(Duration.ofMinutes(1));
        jdbc.sql("""
                        INSERT INTO customer_orders
                        (id, total, status, created_at, kitchen_id, location_id,
                         updated_at, required_at)
                        VALUES
                        (:id, :total, :status, :created, :kitchen, :location,
                         :updated, :required)
                        """)
                .param("id", order)
                .param("total", total)
                .param("status", status)
                .param("created", postgresTime(created))
                .param("kitchen", KITCHEN)
                .param("location", LOCATION)
                .param("updated", postgresTime(now))
                .param("required", postgresTime(now))
                .update();
    }

    private void insertReviewedReceipt(
            String receipt,
            int version,
            List<ReviewedLine> lines
    ) {
        Instant now = Instant.now();
        LocalDate purchaseDate = kitchenDate(now);
        jdbc.sql("""
                        INSERT INTO receipt_imports
                        (id, original_filename, object_uri, status,
                         purchase_date, created_at, kitchen_id, location_id,
                         version, updated_at)
                        VALUES
                        (:id, 'receipt.pdf', :uri, 'REVIEW_REQUIRED',
                         :purchaseDate, :createdAt, :kitchen, :location,
                         :version, :updatedAt)
                        """)
                .param("id", receipt)
                .param("uri", "test://" + receipt)
                .param("purchaseDate", purchaseDate)
                .param("createdAt", postgresTime(now.minusSeconds(1)))
                .param("kitchen", KITCHEN)
                .param("location", LOCATION)
                .param("version", version)
                .param("updatedAt", postgresTime(now))
                .update();

        for (ReviewedLine line : lines) {
            jdbc.sql("""
                            INSERT INTO receipt_items
                            (id, receipt_id, raw_name, canonical_name,
                             quantity, ingredient_id, unit, confidence, selected,
                             source_quantity, source_unit, expires_at,
                             expiry_provenance, review_status, reviewed_at, reviewed_by)
                            VALUES
                            (:id, :receipt, :rawName, :canonicalName,
                             :quantity, :ingredient, :unit, 1.0000, TRUE,
                             :quantity, :unit, :expiresAt,
                             'OWNER_CONFIRMED', 'APPROVED', :reviewedAt, 'owner@test')
                            """)
                    .param("id", line.id())
                    .param("receipt", receipt)
                    .param("rawName", line.ingredient())
                    .param("canonicalName", line.ingredient())
                    .param("quantity", new BigDecimal(line.quantity()))
                    .param("ingredient", line.ingredient())
                    .param("unit", line.unit())
                    .param("expiresAt", purchaseDate.plusDays(2))
                    .param("reviewedAt", postgresTime(now))
                    .update();
        }
    }

    private BigDecimal quantity(String lot) {
        return jdbc.sql("""
                        SELECT quantity_remaining
                        FROM stock_lots
                        WHERE id = :id
                        """)
                .param("id", lot)
                .query(BigDecimal.class)
                .single();
    }

    private String status(String lot) {
        return jdbc.sql("SELECT status FROM stock_lots WHERE id = :id")
                .param("id", lot)
                .query(String.class)
                .single();
    }

    private long movementCount(String reference) {
        return jdbc.sql("""
                        SELECT COUNT(*)
                        FROM stock_movements
                        WHERE reference_id = :reference
                        """)
                .param("reference", reference)
                .query(Long.class)
                .single();
    }

    private long outboxCount(String reference) {
        return jdbc.sql("""
                        SELECT COUNT(*)
                        FROM analytics_outbox
                        WHERE correlation_id = :reference
                           OR entity_id = :reference
                        """)
                .param("reference", reference)
                .query(Long.class)
                .single();
    }

    private long receiptActivityCount(String receipt) {
        return jdbc.sql("""
                        SELECT COUNT(*)
                        FROM activity_events
                        WHERE description LIKE :pattern
                        """)
                .param("pattern", "%" + receipt + "%")
                .query(Long.class)
                .single();
    }

    private String receiptStatus(String receipt) {
        return jdbc.sql("SELECT status FROM receipt_imports WHERE id = :id")
                .param("id", receipt)
                .query(String.class)
                .single();
    }

    private LocalDate kitchenDate(Instant instant) {
        return instant.atZone(ZoneId.of("Asia/Kolkata")).toLocalDate();
    }

    private static OffsetDateTime postgresTime(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String idForReceiptKey(String receipt) {
        return "key-" + receipt;
    }

    private static String id(String prefix) {
        return (prefix + "-" + UUID.randomUUID())
                .substring(0, Math.min(80, prefix.length() + 37));
    }

    private record ReviewedLine(
            String id,
            String ingredient,
            String quantity,
            String unit
    ) {
    }
}
