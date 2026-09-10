package com.bizlama.api.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("memory")
@Transactional
class OperationalCutoverBaselineIntegrationTest {

    private static final String CUTOVER_ID =
            "outbox-v16-operational-baseline";

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private OperationalCutoverBaselineService baseline;

    @Test
    void emitsCanonicalAnchorsOnceAndRequiresBothOwnerConfirmations() {
        Map<String, Object> held = jdbc.sql("""
                        SELECT order_id, line_number, ingredient_id,
                               recipe_version_id, location_id
                        FROM analytics_order_demand_cutover_baseline
                        ORDER BY order_id, line_number, ingredient_id
                        LIMIT 1
                        """)
                .query((result, row) -> Map.<String, Object>of(
                        "order", result.getString("order_id"),
                        "line", result.getInt("line_number"),
                        "ingredient", result.getString("ingredient_id"),
                        "recipe", result.getString("recipe_version_id"),
                        "location", result.getString("location_id")
                ))
                .single();

        jdbc.sql("""
                        UPDATE recipe_versions
                        SET yield_provenance = 'OWNER_CONFIRMED'
                        """)
                .update();
        jdbc.sql("""
                        UPDATE recipe_versions
                        SET yield_provenance = 'LEGACY_PER_ITEM_SCHEMA'
                        WHERE id = :recipe
                        """)
                .param("recipe", held.get("recipe"))
                .update();
        jdbc.sql("""
                        UPDATE order_items
                        SET recipe_pin_provenance = 'LEGACY_RECONSTRUCTED'
                        WHERE order_id = :order AND line_number = :line
                        """)
                .param("order", held.get("order"))
                .param("line", held.get("line"))
                .update();
        jdbc.sql("""
                        UPDATE analytics_order_demand_cutover_baseline
                        SET emission_state = 'REVIEW_REQUIRED',
                            review_reason_code = 'LEGACY_RECIPE_PIN',
                            last_error = 'Held by test for owner confirmation.'
                        WHERE order_id = :order
                          AND line_number = :line
                          AND ingredient_id = :ingredient
                        """)
                .param("order", held.get("order"))
                .param("line", held.get("line"))
                .param("ingredient", held.get("ingredient"))
                .update();

        drainAllPending();

        assertThat(baselineState(held)).isEqualTo("REVIEW_REQUIRED");
        assertThat(baselineEventId(held)).isNull();
        assertThat(scopeManifestState(held)).isEqualTo("REVIEW_REQUIRED");
        assertThat(jdbc.sql("""
                        SELECT COUNT(*)
                        FROM analytics_outbox
                        WHERE event_type = 'OPERATIONAL_CUTOVER_SCOPE_READY'
                          AND kitchen_id = 'kitchen-default'
                          AND entity_id = :location
                        """)
                .param("location", held.get("location"))
                .query(Long.class)
                .single()).isZero();
        assertThat(jdbc.sql("""
                        SELECT COUNT(*)
                        FROM analytics_outbox
                        WHERE event_type = 'INVENTORY_BALANCE_BASELINED'
                          AND correlation_id = :cutover
                        """)
                .param("cutover", CUTOVER_ID)
                .query(Long.class)
                .single()).isEqualTo(
                        count("analytics_inventory_cutover_baseline")
                );
        assertThat(jdbc.sql("""
                        SELECT COUNT(*)
                        FROM analytics_cutover_scope_manifests
                        WHERE expected_inventory_lot_count = 0
                          AND expected_order_demand_row_count = 0
                          AND emission_state = 'EMITTED'
                        """)
                .query(Long.class)
                .single()).as("zero-row scope has a durable ready marker")
                .isPositive();

        jdbc.sql("""
                        UPDATE order_items
                        SET recipe_pin_provenance = 'OWNER_CONFIRMED'
                        WHERE order_id = :order AND line_number = :line
                        """)
                .param("order", held.get("order"))
                .param("line", held.get("line"))
                .update();
        drainAllPending();

        assertThat(baselineState(held)).isEqualTo("REVIEW_REQUIRED");
        assertThat(baselineReason(held)).isEqualTo("LEGACY_RECIPE_YIELD");
        assertThat(baselineEventId(held)).isNull();
        assertThat(scopeManifestState(held)).isEqualTo("REVIEW_REQUIRED");

        jdbc.sql("""
                        UPDATE recipe_versions
                        SET yield_provenance = 'OWNER_CONFIRMED'
                        WHERE id = :recipe
                        """)
                .param("recipe", held.get("recipe"))
                .update();
        drainAllPending();

        assertThat(baselineState(held)).isEqualTo("EMITTED");
        assertThat(baselineEventId(held)).isNotBlank();
        assertThat(scopeManifestState(held)).isEqualTo("EMITTED");
        assertThat(jdbc.sql("""
                        SELECT payload_json
                        FROM analytics_outbox
                        WHERE event_id = :event
                        """)
                .param("event", baselineEventId(held))
                .query(String.class)
                .single())
                .contains("\"cutoverId\":\"" + CUTOVER_ID + "\"")
                .contains("\"orderOccurredAt\":");
        assertThat(jdbc.sql("""
                        SELECT COUNT(*)
                        FROM analytics_order_demand_cutover_baseline baseline
                        JOIN analytics_outbox event
                          ON event.id = baseline.outbox_event_id
                        WHERE event.occurred_at < baseline.captured_at
                        """)
                .query(Long.class)
                .single()).as("baseline events are discoverable when emitted late")
                .isZero();
        assertThat(jdbc.sql("""
                        SELECT payload_json
                        FROM analytics_outbox
                        WHERE event_type = 'OPERATIONAL_CUTOVER_SCOPE_READY'
                          AND kitchen_id = 'kitchen-default'
                          AND entity_id = :location
                        """)
                .param("location", held.get("location"))
                .query(String.class)
                .single())
                .contains("\"cutoverId\":\"" + CUTOVER_ID + "\"")
                .contains("\"locationId\":\""
                        + held.get("location") + "\"")
                .contains("\"expectedInventoryLotCount\":")
                .contains("\"expectedOrderDemandRowCount\":")
                .contains("\"scopeReadyAt\":");

        long eventCount = count("analytics_outbox");
        OperationalCutoverBaselineService.DrainResult replay =
                baseline.drainBatch();
        assertThat(replay.emitted()).isZero();
        assertThat(replay.reviewRequired()).isZero();
        assertThat(count("analytics_outbox")).isEqualTo(eventCount);
        assertThat(jdbc.sql("""
                        SELECT state
                        FROM analytics_cutover_runs
                        WHERE id = :cutover
                        """)
                .param("cutover", CUTOVER_ID)
                .query(String.class)
                .single()).isEqualTo("OUTBOX_READY");
    }

    @Test
    void unsupportedLegacyInventoryUnitIsQuarantinedWithoutAnEvent() {
        String lot = jdbc.sql("""
                        SELECT lot_id
                        FROM analytics_inventory_cutover_baseline
                        ORDER BY lot_id
                        LIMIT 1
                        """)
                .query(String.class)
                .single();
        jdbc.sql("""
                        UPDATE analytics_inventory_cutover_baseline
                        SET stored_unit = 'cup'
                        WHERE cutover_id = :cutover AND lot_id = :lot
                        """)
                .param("cutover", CUTOVER_ID)
                .param("lot", lot)
                .update();

        drainAllPending();

        assertThat(jdbc.sql("""
                        SELECT emission_state
                        FROM analytics_inventory_cutover_baseline
                        WHERE cutover_id = :cutover AND lot_id = :lot
                        """)
                .param("cutover", CUTOVER_ID)
                .param("lot", lot)
                .query(String.class)
                .single()).isEqualTo("REVIEW_REQUIRED");
        assertThat(jdbc.sql("""
                        SELECT review_reason_code
                        FROM analytics_inventory_cutover_baseline
                        WHERE cutover_id = :cutover AND lot_id = :lot
                        """)
                .param("cutover", CUTOVER_ID)
                .param("lot", lot)
                .query(String.class)
                .single()).isEqualTo("DATA_CONTRACT_ERROR");
        assertThat(jdbc.sql("""
                        SELECT COUNT(*)
                        FROM analytics_outbox
                        WHERE event_type = 'INVENTORY_BALANCE_BASELINED'
                          AND entity_id = :lot
                        """)
                .param("lot", lot)
                .query(Long.class)
                .single()).isZero();
        assertThat(jdbc.sql("""
                        SELECT emission_state
                        FROM analytics_cutover_scope_manifests
                        WHERE kitchen_id = 'kitchen-default'
                          AND location_id = 'location-main'
                        """)
                .query(String.class)
                .single()).isEqualTo("REVIEW_REQUIRED");
        assertThat(jdbc.sql("""
                        SELECT state
                        FROM analytics_cutover_runs
                        WHERE id = :cutover
                        """)
                .param("cutover", CUTOVER_ID)
                .query(String.class)
                .single()).isEqualTo("REVIEW_REQUIRED");
    }

    @Test
    void changedScopeCountIsQuarantinedWithoutAReadyEvent() {
        jdbc.sql("""
                        UPDATE recipe_versions
                        SET yield_provenance = 'OWNER_CONFIRMED'
                        """)
                .update();
        jdbc.sql("""
                        UPDATE analytics_cutover_scope_manifests
                        SET expected_inventory_lot_count =
                            expected_inventory_lot_count + 1
                        WHERE cutover_id = :cutover
                          AND kitchen_id = 'kitchen-default'
                          AND location_id = 'location-main'
                        """)
                .param("cutover", CUTOVER_ID)
                .update();

        drainAllPending();

        assertThat(jdbc.sql("""
                        SELECT review_reason_code
                        FROM analytics_cutover_scope_manifests
                        WHERE cutover_id = :cutover
                          AND kitchen_id = 'kitchen-default'
                          AND location_id = 'location-main'
                        """)
                .param("cutover", CUTOVER_ID)
                .query(String.class)
                .single()).isEqualTo("BASELINE_COUNT_MISMATCH");
        assertThat(jdbc.sql("""
                        SELECT COUNT(*)
                        FROM analytics_outbox
                        WHERE event_type = 'OPERATIONAL_CUTOVER_SCOPE_READY'
                          AND kitchen_id = 'kitchen-default'
                          AND entity_id = 'location-main'
                        """)
                .query(Long.class)
                .single()).isZero();
        assertThat(jdbc.sql("""
                        SELECT state
                        FROM analytics_cutover_runs
                        WHERE id = :cutover
                        """)
                .param("cutover", CUTOVER_ID)
                .query(String.class)
                .single()).isEqualTo("REVIEW_REQUIRED");
    }

    private void drainAllPending() {
        for (int attempt = 0; attempt < 20; attempt++) {
            baseline.drainBatch();
            if (pending() == 0) {
                return;
            }
        }
        assertThat(pending()).as("bounded cutover drain").isZero();
    }

    private long pending() {
        return jdbc.sql("""
                        SELECT
                          (SELECT COUNT(*)
                           FROM analytics_inventory_cutover_baseline
                           WHERE emission_state = 'PENDING')
                          +
                          (SELECT COUNT(*)
                           FROM analytics_order_demand_cutover_baseline
                           WHERE emission_state = 'PENDING')
                          +
                          (SELECT COUNT(*)
                           FROM analytics_cutover_scope_manifests
                           WHERE emission_state = 'PENDING')
                        """)
                .query(Long.class)
                .single();
    }

    private String baselineState(Map<String, Object> row) {
        return jdbc.sql("""
                        SELECT emission_state
                        FROM analytics_order_demand_cutover_baseline
                        WHERE order_id = :order
                          AND line_number = :line
                          AND ingredient_id = :ingredient
                        """)
                .param("order", row.get("order"))
                .param("line", row.get("line"))
                .param("ingredient", row.get("ingredient"))
                .query(String.class)
                .single();
    }

    private String baselineReason(Map<String, Object> row) {
        return jdbc.sql("""
                        SELECT review_reason_code
                        FROM analytics_order_demand_cutover_baseline
                        WHERE order_id = :order
                          AND line_number = :line
                          AND ingredient_id = :ingredient
                        """)
                .param("order", row.get("order"))
                .param("line", row.get("line"))
                .param("ingredient", row.get("ingredient"))
                .query(String.class)
                .single();
    }

    private String baselineEventId(Map<String, Object> row) {
        return jdbc.sql("""
                        SELECT outbox_event_id
                        FROM analytics_order_demand_cutover_baseline
                        WHERE order_id = :order
                          AND line_number = :line
                          AND ingredient_id = :ingredient
                        """)
                .param("order", row.get("order"))
                .param("line", row.get("line"))
                .param("ingredient", row.get("ingredient"))
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private String scopeManifestState(Map<String, Object> row) {
        return jdbc.sql("""
                        SELECT emission_state
                        FROM analytics_cutover_scope_manifests
                        WHERE cutover_id = :cutover
                          AND kitchen_id = 'kitchen-default'
                          AND location_id = :location
                        """)
                .param("cutover", CUTOVER_ID)
                .param("location", row.get("location"))
                .query(String.class)
                .single();
    }

    private long count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table)
                .query(Long.class)
                .single();
    }
}
