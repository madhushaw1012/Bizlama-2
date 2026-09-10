package com.bizlama.api.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class OperationalCutoverBaselineMigrationTest {

    @Test
    void populatedV15UpgradeAnchorsEveryLotAndSkipsExistingDemandEvent() {
        DataSource dataSource = database();
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("15"))
                .load()
                .migrate();

        JdbcClient jdbc = JdbcClient.create(dataSource);
        long lotCount = count(jdbc, "stock_lots");
        long demandComponents = jdbc.sql("""
                        SELECT COUNT(*)
                        FROM customer_orders orders
                        JOIN order_items item ON item.order_id = orders.id
                        JOIN recipe_ingredients recipe_item
                          ON recipe_item.recipe_version_id =
                             item.recipe_version_id
                        """)
                .query(Long.class)
                .single();
        Map<String, Object> existing = jdbc.sql("""
                        SELECT orders.id AS order_id,
                               orders.kitchen_id,
                               orders.created_at,
                               item.line_number,
                               recipe_item.ingredient_id
                        FROM customer_orders orders
                        JOIN order_items item ON item.order_id = orders.id
                        JOIN recipe_ingredients recipe_item
                          ON recipe_item.recipe_version_id =
                             item.recipe_version_id
                        ORDER BY orders.id, item.line_number,
                                 recipe_item.ingredient_id
                        LIMIT 1
                        """)
                .query((result, row) -> Map.<String, Object>of(
                        "order", result.getString("order_id"),
                        "kitchen", result.getString("kitchen_id"),
                        "occurred", result.getObject("created_at"),
                        "line", result.getInt("line_number"),
                        "ingredient", result.getString("ingredient_id")
                ))
                .single();

        String eventId = "existing-demand-" + UUID.randomUUID();
        String entityId = existing.get("order") + ":"
                + existing.get("line") + ":" + existing.get("ingredient");
        String deduplicationKey = "order-ingredient-demand:" + entityId;
        OffsetDateTime recordedAt = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO analytics_outbox
                        (id, event_id, event_type, schema_version, kitchen_id,
                         aggregate_type, aggregate_id, entity_type, entity_id,
                         occurred_at, recorded_at, correlation_id,
                         deduplication_key, payload_json,
                         source_metadata_json, state, attempt_count,
                         next_attempt_at, published_at)
                        VALUES
                        (:id, :id, 'ORDER_INGREDIENT_DEMAND', 1, :kitchen,
                         'order_ingredient_demand', :entity,
                         'order_ingredient_demand', :entity,
                         :occurred, :recorded, :order, :dedup,
                         '{}', '{}', 'PUBLISHED', 1, NULL, :recorded)
                        """)
                .param("id", eventId)
                .param("kitchen", existing.get("kitchen"))
                .param("entity", entityId)
                .param("occurred", existing.get("occurred"))
                .param("recorded", recordedAt)
                .param("order", existing.get("order"))
                .param("dedup", deduplicationKey)
                .update();

        Flyway latest = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();
        latest.migrate();

        assertThat(latest.info().current().getVersion().getVersion())
                .isGreaterThanOrEqualTo("18");
        assertThat(count(jdbc, "analytics_inventory_cutover_baseline"))
                .isEqualTo(lotCount);
        assertThat(count(jdbc, "analytics_order_demand_cutover_baseline"))
                .isEqualTo(demandComponents - 1);
        assertThat(jdbc.sql("""
                        SELECT COUNT(*)
                        FROM analytics_order_demand_cutover_baseline baseline
                        WHERE CONCAT(
                          'order-ingredient-demand:',
                          baseline.order_id,
                          ':',
                          baseline.line_number,
                          ':',
                          baseline.ingredient_id
                        ) = :dedup
                        """)
                .param("dedup", deduplicationKey)
                .query(Long.class)
                .single()).isZero();
        assertThat(jdbc.sql("""
                        SELECT COUNT(*)
                        FROM analytics_inventory_cutover_baseline baseline
                        JOIN stock_lots lot ON lot.id = baseline.lot_id
                        WHERE baseline.kitchen_id <> lot.kitchen_id
                           OR baseline.location_id <> lot.location_id
                           OR baseline.ingredient_id <> lot.ingredient_id
                        """)
                .query(Long.class)
                .single()).isZero();
        assertThat(jdbc.sql("""
                        SELECT COUNT(DISTINCT captured_at)
                        FROM analytics_inventory_cutover_baseline
                        """)
                .query(Long.class)
                .single()).isLessThanOrEqualTo(1);
        assertThat(count(jdbc, "analytics_cutover_scope_manifests"))
                .isEqualTo(count(jdbc, "kitchen_locations"));
        assertThat(jdbc.sql("""
                        SELECT COUNT(*)
                        FROM analytics_cutover_scope_manifests manifest
                        WHERE manifest.expected_inventory_lot_count <> (
                          SELECT COUNT(*)
                          FROM analytics_inventory_cutover_baseline inventory
                          WHERE inventory.cutover_id = manifest.cutover_id
                            AND inventory.kitchen_id = manifest.kitchen_id
                            AND inventory.location_id = manifest.location_id
                        )
                           OR manifest.expected_order_demand_row_count <> (
                          SELECT COUNT(*)
                          FROM analytics_order_demand_cutover_baseline demand
                          WHERE demand.cutover_id = manifest.cutover_id
                            AND demand.kitchen_id = manifest.kitchen_id
                            AND demand.location_id = manifest.location_id
                        )
                        """)
                .query(Long.class)
                .single()).isZero();
        assertThat(jdbc.sql("""
                        SELECT COUNT(*)
                        FROM analytics_cutover_scope_manifests
                        WHERE expected_inventory_lot_count = 0
                          AND expected_order_demand_row_count = 0
                        """)
                .query(Long.class)
                .single()).as("zero-row locations receive an explicit marker")
                .isPositive();
        assertThat(jdbc.sql("""
                        SELECT COUNT(*)
                        FROM analytics_cutover_scope_manifests
                        WHERE emission_state <> 'PENDING'
                           OR outbox_event_id IS NOT NULL
                        """)
                .query(Long.class)
                .single()).isZero();
        assertThat(jdbc.sql("""
                        SELECT state
                        FROM analytics_cutover_runs
                        WHERE id = 'outbox-v16-operational-baseline'
                        """)
                .query(String.class)
                .single()).isIn("PENDING", "REVIEW_REQUIRED");
    }

    private long count(JdbcClient jdbc, String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table)
                .query(Long.class)
                .single();
    }

    private DataSource database() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl(
                "jdbc:h2:mem:cutover-upgrade-" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        );
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }
}
