package com.bizlama.api.integrity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class TenantScopeMigrationCompatibilityTest {

    @Test
    void populatedV13UpgradeQuarantinesAmbiguousScopeAndInstallsNoDefaults() {
        DataSource dataSource = database();
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("13"))
                .load()
                .migrate();

        JdbcClient jdbc = JdbcClient.create(dataSource);
        String suffix = UUID.randomUUID().toString();
        String kitchen = "upgrade-kitchen-" + suffix;
        String firstLocation = "upgrade-location-a-" + suffix;
        String secondLocation = "upgrade-location-b-" + suffix;
        String activity = "upgrade-activity-" + suffix;
        String aiAction = "upgrade-ai-" + suffix;
        String proposal = "upgrade-proposal-" + suffix;
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        jdbc.sql("""
                        INSERT INTO kitchens
                        (id, name, timezone, currency, active)
                        VALUES (:id, 'Upgrade tenant', 'UTC', 'USD', TRUE)
                        """)
                .param("id", kitchen)
                .update();
        jdbc.sql("""
                        INSERT INTO kitchen_locations
                        (id, kitchen_id, name, location_type, active)
                        VALUES
                        (:first, :kitchen, 'Upgrade first', 'KITCHEN', TRUE),
                        (:second, :kitchen, 'Upgrade second', 'KITCHEN', TRUE)
                        """)
                .param("first", firstLocation)
                .param("second", secondLocation)
                .param("kitchen", kitchen)
                .update();
        jdbc.sql("""
                        INSERT INTO activity_events
                        (id, event_type, description, occurred_at)
                        VALUES (:id, 'Upgrade', 'Ambiguous legacy activity', :now)
                        """)
                .param("id", activity)
                .param("now", now)
                .update();
        jdbc.sql("""
                        INSERT INTO ai_actions
                        (id, action_type, original_input, normalized_item_id,
                         confidence, decision, explanation, occurred_at)
                        VALUES
                        (:id, 'UPGRADE', 'ambiguous legacy input', NULL,
                         1.0000, 'REVIEW', 'Ambiguous legacy AI audit', :now)
                        """)
                .param("id", aiAction)
                .param("now", now)
                .update();
        jdbc.sql("""
                        INSERT INTO kitchen_event_proposals
                        (id, kitchen_id, version, original_input, events_json,
                         risk_tier, status, expires_at, created_at)
                        VALUES
                        (:id, :kitchen, 1, 'ambiguous legacy proposal', '[]',
                         'HIGH', 'PENDING', :expires, :now)
                        """)
                .param("id", proposal)
                .param("kitchen", kitchen)
                .param("expires", now.plusMinutes(15))
                .param("now", now)
                .update();

        Flyway latest = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();
        latest.migrate();

        assertThat(latest.info().current().getVersion().getVersion())
                .isEqualTo("21");
        assertThat(scope(jdbc, "activity_events", activity))
                .containsExactly(
                        "kitchen-legacy-unattributed",
                        "location-legacy-unattributed"
                );
        assertThat(scope(jdbc, "ai_actions", aiAction))
                .containsExactly(
                        "kitchen-legacy-unattributed",
                        "location-legacy-unattributed"
                );

        String quarantinedLocation = "legacy-unscoped-" + kitchen;
        assertThat(jdbc.sql("""
                        SELECT location_id
                        FROM kitchen_event_proposals
                        WHERE id = :id
                        """)
                .param("id", proposal)
                .query(String.class)
                .single()).isEqualTo(quarantinedLocation);
        assertThat(jdbc.sql("""
                        SELECT active
                        FROM kitchen_locations
                        WHERE id = :id AND kitchen_id = :kitchen
                        """)
                .param("id", quarantinedLocation)
                .param("kitchen", kitchen)
                .query(Boolean.class)
                .single()).isFalse();

        Long tenantDefaults = jdbc.sql("""
                        SELECT COUNT(*)
                        FROM information_schema.columns
                        WHERE LOWER(table_schema) = 'public'
                          AND column_default IS NOT NULL
                          AND (
                            (LOWER(column_name) = 'kitchen_id'
                             AND LOWER(table_name) IN (
                               'recipe_versions', 'recipe_ingredients',
                               'recipe_steps', 'feedback', 'recipe_experiments',
                               'ingredient_aliases', 'unit_conversions',
                               'activity_events', 'ai_actions'))
                            OR
                            (LOWER(column_name) = 'location_id'
                             AND LOWER(table_name) IN (
                               'activity_events', 'ai_actions',
                               'kitchen_event_proposals'))
                          )
                        """)
                .query(Long.class)
                .single();
        assertThat(tenantDefaults).isZero();

        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO activity_events
                        (id, event_type, description, occurred_at)
                        VALUES (:id, 'Invalid', 'Missing tenant scope', :now)
                        """)
                .param("id", "missing-scope-" + suffix)
                .param("now", now)
                .update())
                .isInstanceOf(DataAccessException.class);
    }

    private DataSource database() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl(
                "jdbc:h2:mem:tenant-upgrade-" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        );
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private java.util.List<String> scope(
            JdbcClient jdbc,
            String table,
            String id
    ) {
        return jdbc.sql("SELECT kitchen_id, location_id FROM " + table
                        + " WHERE id = :id")
                .param("id", id)
                .query((rs, row) -> java.util.List.of(
                        rs.getString("kitchen_id"),
                        rs.getString("location_id")
                ))
                .single();
    }
}
