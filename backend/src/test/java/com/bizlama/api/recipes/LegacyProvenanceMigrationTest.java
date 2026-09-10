package com.bizlama.api.recipes;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class LegacyProvenanceMigrationTest {

    @Test
    void v17ClassifiesHistoricalPinsAndQuarantinesAmbiguousBaseline()
            throws Exception {
        String url = "jdbc:h2:mem:legacy-provenance-"
                + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .target("16")
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO ingredients
                    (id, name, base_unit, active, created_at, kitchen_id)
                    VALUES
                    ('migration-provenance-ingredient',
                     'Migration provenance ingredient',
                     'g', TRUE, CURRENT_TIMESTAMP, 'kitchen-default')
                    """);
            statement.executeUpdate("""
                    INSERT INTO dishes
                    (id, name, price, active, created_at, kitchen_id)
                    VALUES
                    ('migration-ambiguous-dish', 'Migration ambiguous dish',
                     10.00, TRUE, CURRENT_TIMESTAMP, 'kitchen-default'),
                    ('migration-unambiguous-dish', 'Migration unambiguous dish',
                     10.00, TRUE, CURRENT_TIMESTAMP, 'kitchen-default')
                    """);
            statement.executeUpdate("""
                    INSERT INTO recipe_versions
                    (id, dish_id, version_number, change_reason, active,
                     created_at, yield_quantity, yield_unit, kitchen_id)
                    VALUES
                    ('migration-ambiguous-v1', 'migration-ambiguous-dish', 1,
                     'First possible version', FALSE, CURRENT_TIMESTAMP,
                     1.000, 'each', 'kitchen-default'),
                    ('migration-ambiguous-v2', 'migration-ambiguous-dish', 2,
                     'Second possible version', FALSE, CURRENT_TIMESTAMP,
                     1.000, 'each', 'kitchen-default'),
                    ('migration-unambiguous-v1', 'migration-unambiguous-dish', 1,
                     'Only possible version', FALSE, CURRENT_TIMESTAMP,
                     1.000, 'each', 'kitchen-default')
                    """);
            statement.executeUpdate("""
                    INSERT INTO recipe_ingredients
                    (recipe_version_id, ingredient_id, quantity, unit, kitchen_id)
                    VALUES
                    ('migration-ambiguous-v1',
                     'migration-provenance-ingredient', 10.000, 'g',
                     'kitchen-default'),
                    ('migration-ambiguous-v2',
                     'migration-provenance-ingredient', 20.000, 'g',
                     'kitchen-default'),
                    ('migration-unambiguous-v1',
                     'migration-provenance-ingredient', 30.000, 'g',
                     'kitchen-default')
                    """);
            statement.executeUpdate("""
                    INSERT INTO customer_orders
                    (id, total, status, created_at, required_at,
                     kitchen_id, location_id)
                    VALUES
                    ('migration-ambiguous-order', 10.00, 'QUEUED',
                     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                     'kitchen-default', 'location-main'),
                    ('migration-unambiguous-order', 10.00, 'QUEUED',
                     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                     'kitchen-default', 'location-main'),
                    ('migration-captured-order', 10.00, 'QUEUED',
                     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                     'kitchen-default', 'location-main')
                    """);
            statement.executeUpdate("""
                    INSERT INTO order_items
                    (order_id, line_number, dish_id, quantity, unit_price,
                     recipe_version_id, prepared_quantity)
                    VALUES
                    ('migration-ambiguous-order', 1,
                     'migration-ambiguous-dish', 1, 10.00,
                     'migration-ambiguous-v2', 0),
                    ('migration-unambiguous-order', 1,
                     'migration-unambiguous-dish', 1, 10.00,
                     'migration-unambiguous-v1', 0),
                    ('migration-captured-order', 1,
                     'migration-ambiguous-dish', 1, 10.00,
                     'migration-ambiguous-v1', 0)
                    """);
            statement.executeUpdate("""
                    INSERT INTO analytics_outbox
                    (id, kitchen_id, event_type, aggregate_type, aggregate_id,
                     payload_json, occurred_at, correlation_id)
                    VALUES
                    ('migration-captured-demand-event', 'kitchen-default',
                     'ORDER_INGREDIENT_DEMAND', 'order_ingredient_demand',
                     'migration-captured-order:1:migration-provenance-ingredient',
                     '{}', CURRENT_TIMESTAMP, 'migration-captured-order')
                    """);
            statement.executeUpdate("""
                    INSERT INTO analytics_order_demand_cutover_baseline
                    (cutover_id, order_id, line_number, ingredient_id,
                     kitchen_id, location_id, dish_id, recipe_version_id,
                     ordered_quantity, recipe_ingredient_quantity,
                     recipe_ingredient_unit, recipe_yield_quantity,
                     recipe_yield_unit, ingredient_name, ingredient_base_unit,
                     order_occurred_at, required_at, captured_at)
                    VALUES
                    ('outbox-v16-operational-baseline',
                     'migration-ambiguous-order', 1,
                     'migration-provenance-ingredient',
                     'kitchen-default', 'location-main',
                     'migration-ambiguous-dish', 'migration-ambiguous-v2',
                     1, 20.000, 'g', 1.000, 'each',
                     'Migration provenance ingredient', 'g',
                     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """);
            statement.executeUpdate("""
                    INSERT INTO analytics_order_demand_cutover_baseline
                    (cutover_id, order_id, line_number, ingredient_id,
                     kitchen_id, location_id, dish_id, recipe_version_id,
                     ordered_quantity, recipe_ingredient_quantity,
                     recipe_ingredient_unit, recipe_yield_quantity,
                     recipe_yield_unit, ingredient_name, ingredient_base_unit,
                     order_occurred_at, required_at, captured_at)
                    VALUES
                    ('outbox-v16-operational-baseline',
                     'migration-unambiguous-order', 1,
                     'migration-provenance-ingredient',
                     'kitchen-default', 'location-main',
                     'migration-unambiguous-dish', 'migration-unambiguous-v1',
                     1, 30.000, 'g', 1.000, 'each',
                     'Migration provenance ingredient', 'g',
                     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """);
        }

        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            assertThat(provenance(statement, "migration-ambiguous-order"))
                    .isEqualTo("LEGACY_RECONSTRUCTED");
            assertThat(provenance(statement, "migration-unambiguous-order"))
                    .isEqualTo("LEGACY_UNAMBIGUOUS");
            assertThat(provenance(statement, "migration-captured-order"))
                    .isEqualTo("CAPTURED_AT_ORDER");

            try (ResultSet result = statement.executeQuery("""
                    SELECT yield_provenance
                    FROM recipe_versions
                    WHERE id = 'migration-ambiguous-v1'
                    """)) {
                assertThat(singleText(result))
                        .isEqualTo("LEGACY_PER_ITEM_SCHEMA");
            }
            try (ResultSet result = statement.executeQuery("""
                    SELECT emission_state, review_reason_code, last_error
                    FROM analytics_order_demand_cutover_baseline
                    WHERE order_id = 'migration-ambiguous-order'
                    """)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("emission_state"))
                        .isEqualTo("REVIEW_REQUIRED");
                assertThat(result.getString("review_reason_code"))
                        .isEqualTo("LEGACY_RECIPE_PIN");
                assertThat(result.getString("last_error"))
                        .contains("owner confirmation");
            }
            try (ResultSet result = statement.executeQuery("""
                    SELECT emission_state, review_reason_code, last_error
                    FROM analytics_order_demand_cutover_baseline
                    WHERE order_id = 'migration-unambiguous-order'
                    """)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("emission_state"))
                        .isEqualTo("REVIEW_REQUIRED");
                assertThat(result.getString("review_reason_code"))
                        .isEqualTo("LEGACY_RECIPE_YIELD");
                assertThat(result.getString("last_error"))
                        .contains("owner confirmation");
            }


            statement.executeUpdate("""
                    INSERT INTO customer_orders
                    (id, total, status, created_at, required_at,
                     kitchen_id, location_id)
                    VALUES
                    ('migration-post-v17-order', 10.00, 'QUEUED',
                     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                     'kitchen-default', 'location-main')
                    """);
            statement.executeUpdate("""
                    INSERT INTO order_items
                    (order_id, line_number, dish_id, quantity, unit_price,
                     recipe_version_id, prepared_quantity)
                    VALUES
                    ('migration-post-v17-order', 1,
                     'migration-unambiguous-dish', 1, 10.00,
                     'migration-unambiguous-v1', 0)
                    """);
            assertThat(provenance(statement, "migration-post-v17-order"))
                    .isEqualTo("CAPTURED_AT_ORDER");
        }
    }

    private String provenance(Statement statement, String orderId)
            throws Exception {
        try (ResultSet result = statement.executeQuery("""
                SELECT recipe_pin_provenance
                FROM order_items
                WHERE order_id = '%s'
                """.formatted(orderId))) {
            return singleText(result);
        }
    }

    private String singleText(ResultSet result) throws Exception {
        assertThat(result.next()).isTrue();
        return result.getString(1);
    }
}
