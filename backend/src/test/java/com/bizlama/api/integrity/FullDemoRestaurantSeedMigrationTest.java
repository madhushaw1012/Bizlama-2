package com.bizlama.api.integrity;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.Test;

class FullDemoRestaurantSeedMigrationTest {

    private static final String MIGRATION =
            "/db/migration/V20__full_demo_restaurant_seed.sql";

    @Test
    void latestMigrationsBuildACompleteConflictSafeDemoRestaurant() throws Exception {
        String url = "jdbc:h2:mem:full-demo-restaurant-"
                + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

        Flyway flyway = Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();

        assertThat(flyway.info().current().getVersion().getVersion())
                .isEqualTo("21");

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            assertSeedCounts(statement);
            assertCoherentRecipes(statement);

            try (var input = getClass().getResourceAsStream(MIGRATION)) {
                assertThat(input).isNotNull();
                RunScript.execute(
                        connection,
                        new InputStreamReader(input, UTF_8)
                );
            }

            assertSeedCounts(statement);
            assertCoherentRecipes(statement);
        }
    }

    private void assertSeedCounts(Statement statement) throws Exception {
        assertThat(count(statement, """
                SELECT COUNT(*) FROM ingredients
                WHERE kitchen_id = 'kitchen-default' AND id LIKE 'demo-%'
                """)).isEqualTo(33);
        assertThat(count(statement, """
                SELECT COUNT(*) FROM dishes
                WHERE kitchen_id = 'kitchen-default' AND id LIKE 'demo-%'
                """)).isEqualTo(20);
        assertThat(count(statement, """
                SELECT COUNT(*) FROM recipe_versions
                WHERE kitchen_id = 'kitchen-default' AND id LIKE 'demo-%'
                """)).isEqualTo(20);
        assertThat(count(statement, """
                SELECT COUNT(*) FROM recipe_ingredients
                WHERE kitchen_id = 'kitchen-default'
                  AND recipe_version_id LIKE 'demo-%'
                """)).isEqualTo(175);
        assertThat(count(statement, """
                SELECT COUNT(*) FROM recipe_steps
                WHERE kitchen_id = 'kitchen-default'
                  AND recipe_version_id LIKE 'demo-%'
                """)).isEqualTo(60);
        assertThat(count(statement, """
                SELECT COUNT(*) FROM stock_lots
                WHERE kitchen_id = 'kitchen-default'
                  AND source = 'synthetic-restaurant-seed'
                """)).isEqualTo(60);
        assertThat(count(statement, """
                SELECT COUNT(*) FROM stock_movements
                WHERE kitchen_id = 'kitchen-default'
                  AND reference_type = 'SYNTHETIC_SEED'
                """)).isEqualTo(60);
        assertThat(count(statement, """
                SELECT COUNT(*) FROM feedback
                WHERE kitchen_id = 'kitchen-default' AND id LIKE 'FB-FULL-%'
                """)).isEqualTo(70);
        assertThat(count(statement, """
                SELECT COUNT(*) FROM feedback
                WHERE kitchen_id = 'kitchen-default'
                  AND id LIKE 'FB-EXPERIMENT-%'
                """)).isEqualTo(8);
    }

    private void assertCoherentRecipes(Statement statement) throws Exception {
        assertThat(count(statement, """
                SELECT COUNT(*)
                FROM dishes dish
                LEFT JOIN recipe_versions recipe
                  ON recipe.id = dish.active_recipe_version_id
                 AND recipe.dish_id = dish.id
                 AND recipe.kitchen_id = dish.kitchen_id
                WHERE dish.kitchen_id = 'kitchen-default'
                  AND dish.id LIKE 'demo-%'
                  AND (recipe.id IS NULL OR recipe.active = FALSE)
                """)).isZero();
        assertThat(count(statement, """
                SELECT COUNT(*) FROM recipe_versions
                WHERE kitchen_id = 'kitchen-default'
                  AND id LIKE 'demo-%'
                  AND active_dish_guard IS NULL
                """)).isZero();
        assertThat(count(statement, """
                SELECT COUNT(*)
                FROM recipe_ingredients item
                JOIN ingredients ingredient
                  ON ingredient.id = item.ingredient_id
                 AND ingredient.kitchen_id = item.kitchen_id
                WHERE item.kitchen_id = 'kitchen-default'
                  AND item.recipe_version_id LIKE 'demo-%'
                  AND item.unit <> ingredient.base_unit
                """)).isZero();
        assertThat(count(statement, """
                SELECT COUNT(*)
                FROM dishes dish
                WHERE dish.kitchen_id = 'kitchen-default'
                  AND dish.id LIKE 'demo-%'
                  AND NOT EXISTS (
                    SELECT 1 FROM recipe_ingredients item
                    WHERE item.recipe_version_id = dish.active_recipe_version_id
                    GROUP BY item.recipe_version_id
                    HAVING COUNT(*) >= 3
                  )
                """)).isZero();
    }

    private long count(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }
}
