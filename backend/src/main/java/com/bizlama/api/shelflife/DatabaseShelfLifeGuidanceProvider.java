package com.bizlama.api.shelflife;

import java.util.Optional;

import com.bizlama.api.config.WorkspaceProperties;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "bizlama.shelf-life.mode",
        havingValue = "database",
        matchIfMissing = true
)
public class DatabaseShelfLifeGuidanceProvider
        implements ShelfLifeGuidanceProvider {

    private final JdbcClient jdbc;
    private final WorkspaceProperties workspace;

    public DatabaseShelfLifeGuidanceProvider(
            JdbcClient jdbc,
            WorkspaceProperties workspace
    ) {
        this.jdbc = jdbc;
        this.workspace = workspace;
    }

    @Override
    public Optional<ShelfLifeGuidance> findForIngredient(String ingredientId) {
        return jdbc.sql("""
                        SELECT ingredient_id, min_days, source_name
                        FROM shelf_life_rules
                        WHERE kitchen_id = :kitchen
                          AND ingredient_id = :ingredient
                          AND active = TRUE
                        ORDER BY priority, reviewed_at DESC
                        LIMIT 1
                        """)
                .param("kitchen", workspace.kitchenId())
                .param("ingredient", ingredientId)
                .query((rs, row) -> new ShelfLifeGuidance(
                        rs.getString("ingredient_id"),
                        rs.getInt("min_days"),
                        rs.getString("source_name")
                ))
                .optional();
    }
}