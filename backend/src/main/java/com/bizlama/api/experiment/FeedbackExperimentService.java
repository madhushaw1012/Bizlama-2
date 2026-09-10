package com.bizlama.api.experiment;

import com.bizlama.api.config.JdbcTimestamp;
import com.bizlama.api.config.WorkspaceProperties;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Deterministic feedback-theme counting; it never edits a recipe. */
@Service
public class FeedbackExperimentService {

    static final int FEEDBACK_THRESHOLD = 3;
    private static final List<ThemeRule> THEMES = List.of(
            new ThemeRule(
                    "Dry texture",
                    "Moisture adjustment",
                    List.of("dry", "hard", "moisture", "sauce"),
                    false
            ),
            new ThemeRule(
                    "Too salty",
                    "Salt adjustment",
                    List.of("salty", "too much salt", "over salted"),
                    false
            ),
            new ThemeRule(
                    "Too spicy",
                    "Spice adjustment",
                    List.of("too spicy", "too hot", "over spicy"),
                    false
            ),
            new ThemeRule(
                    "Too sweet",
                    "Sweetness adjustment",
                    List.of("too sweet", "too much sugar"),
                    false
            ),
            new ThemeRule(
                    "Low rating",
                    "Recipe adjustment",
                    List.of(),
                    true
            )
    );

    private final JdbcClient jdbc;
    private final WorkspaceProperties workspace;

    public FeedbackExperimentService(
            JdbcClient jdbc,
            WorkspaceProperties workspace
    ) {
        this.jdbc = jdbc;
        this.workspace = workspace;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void refresh(String recipeVersionId) {
        RecipeContext context = jdbc.sql("""
                        SELECT recipe.dish_id, dish.name
                        FROM recipe_versions recipe
                        JOIN dishes dish
                          ON dish.id = recipe.dish_id
                         AND dish.kitchen_id = recipe.kitchen_id
                        WHERE recipe.id = :recipe
                          AND recipe.kitchen_id = :kitchen
                          AND recipe.active = TRUE
                          AND dish.active = TRUE
                          AND dish.active_recipe_version_id = recipe.id
                        """)
                .param("recipe", recipeVersionId)
                .param("kitchen", workspace.kitchenId())
                .query((rs, row) -> new RecipeContext(
                        rs.getString("dish_id"),
                        rs.getString("name")
                ))
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "Experiment evidence must use the active recipe version."
                ));

        List<FeedbackEvidence> evidence = jdbc.sql("""
                        SELECT feedback_text, rating
                        FROM feedback
                        WHERE recipe_id = :recipe
                          AND kitchen_id = :kitchen
                        ORDER BY occurred_at, id
                        """)
                .param("recipe", recipeVersionId)
                .param("kitchen", workspace.kitchenId())
                .query((rs, row) -> new FeedbackEvidence(
                        rs.getString("feedback_text"),
                        rs.getInt("rating")
                ))
                .list();
        ThemeScore score = score(evidence);
        ExistingExperiment existing = jdbc.sql("""
                        SELECT id, status
                        FROM recipe_experiments
                        WHERE dish_id = :dish
                          AND recipe_version_id = :recipe
                          AND kitchen_id = :kitchen
                          AND status IN ('PROPOSED', 'ACTIVE')
                        ORDER BY CASE WHEN status = 'ACTIVE' THEN 0 ELSE 1 END,
                                 updated_at DESC,
                                 id
                        LIMIT 1
                        FOR UPDATE
                        """)
                .param("dish", context.dishId())
                .param("recipe", recipeVersionId)
                .param("kitchen", workspace.kitchenId())
                .query((rs, row) -> new ExistingExperiment(
                        rs.getString("id"),
                        rs.getString("status")
                ))
                .optional()
                .orElse(null);

        if (score.count() < FEEDBACK_THRESHOLD) {
            if (existing != null) {
                updateCounts(existing, score, evidence.size());
            }
            return;
        }
        if (existing != null) {
            updateCounts(existing, score, evidence.size());
            return;
        }

        Instant now = Instant.now();
        String id = "EXP-" + UUID.nameUUIDFromBytes((
                workspace.kitchenId() + "|" + context.dishId() + "|"
                        + recipeVersionId + "|" + score.rule().theme()
        ).getBytes(StandardCharsets.UTF_8));
        jdbc.sql("""
                        INSERT INTO recipe_experiments
                        (id, dish_id, recipe_version_id, theme, theme_count,
                         feedback_count, metric_name, current_value,
                         proposed_value, value_unit, test_duration_days,
                         status, version, created_at, updated_at, created_by,
                         kitchen_id)
                        VALUES
                        (:id, :dish, :recipe, :theme, :themeCount,
                         :feedbackCount, :metric, 0, 1, 'review step', 7,
                         'PROPOSED', 1, :now, :now, 'deterministic-feedback-v1',
                         :kitchen)
                        """)
                .param("id", id)
                .param("dish", context.dishId())
                .param("recipe", recipeVersionId)
                .param("theme", score.rule().theme())
                .param("themeCount", score.count())
                .param("feedbackCount", evidence.size())
                .param("metric", score.rule().metric())
                .param("now", JdbcTimestamp.utc(now))
                .param("kitchen", workspace.kitchenId())
                .update();
    }

    private void updateCounts(
            ExistingExperiment existing,
            ThemeScore score,
            int feedbackCount
    ) {
        Instant now = Instant.now();
        if ("ACTIVE".equals(existing.status())) {
            jdbc.sql("""
                            UPDATE recipe_experiments
                            SET theme_count = :themeCount,
                                feedback_count = :feedbackCount,
                                updated_at = :now,
                                version = version + 1
                            WHERE id = :id
                              AND kitchen_id = :kitchen
                            """)
                    .param("themeCount", score.count())
                    .param("feedbackCount", feedbackCount)
                    .param("now", JdbcTimestamp.utc(now))
                    .param("id", existing.id())
                    .param("kitchen", workspace.kitchenId())
                    .update();
            return;
        }
        jdbc.sql("""
                        UPDATE recipe_experiments
                        SET theme = :theme,
                            theme_count = :themeCount,
                            feedback_count = :feedbackCount,
                            metric_name = :metric,
                            current_value = 0,
                            proposed_value = 1,
                            value_unit = 'review step',
                            updated_at = :now,
                            version = version + 1
                        WHERE id = :id
                          AND kitchen_id = :kitchen
                        """)
                .param("theme", score.rule().theme())
                .param("themeCount", score.count())
                .param("feedbackCount", feedbackCount)
                .param("metric", score.rule().metric())
                .param("now", JdbcTimestamp.utc(now))
                .param("id", existing.id())
                .param("kitchen", workspace.kitchenId())
                .update();
    }

    static ThemeScore score(List<FeedbackEvidence> evidence) {
        ThemeRule best = THEMES.getLast();
        int bestCount = 0;
        for (ThemeRule rule : THEMES) {
            int count = (int) evidence.stream()
                    .filter(rule::matches)
                    .count();
            if (count > bestCount) {
                best = rule;
                bestCount = count;
            }
        }
        return new ThemeScore(best, bestCount);
    }

    record FeedbackEvidence(String text, int rating) {
    }

    record ThemeScore(ThemeRule rule, int count) {
    }

    record ThemeRule(
            String theme,
            String metric,
            List<String> terms,
            boolean lowRating
    ) {
        boolean matches(FeedbackEvidence feedback) {
            if (lowRating) {
                return feedback.rating() <= 3;
            }
            String normalized = feedback.text() == null
                    ? ""
                    : feedback.text().toLowerCase(Locale.ROOT);
            return terms.stream().anyMatch(normalized::contains);
        }
    }

    private record RecipeContext(String dishId, String dishName) {
    }

    private record ExistingExperiment(String id, String status) {
    }
}
