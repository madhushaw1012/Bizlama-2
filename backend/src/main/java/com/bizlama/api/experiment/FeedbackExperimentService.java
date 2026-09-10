package com.bizlama.api.experiment;

import com.bizlama.api.config.JdbcTimestamp;
import com.bizlama.api.config.WorkspaceProperties;
import java.math.BigDecimal;
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
                    List.of(
                            "too sweet",
                            "too much sugar",
                            "overly sweet",
                            "sweeter than expected"
                    ),
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

    /**
     * Reconciles feedback that entered through a migration or import rather
     * than the live capture service. Creating a proposal remains deterministic
     * and never changes the active recipe.
     */
    @Transactional
    public void reconcileDish(String dishId) {
        String recipeVersionId = jdbc.sql("""
                        SELECT active_recipe_version_id
                        FROM dishes
                        WHERE id = :dish
                          AND kitchen_id = :kitchen
                          AND active = TRUE
                          AND active_recipe_version_id IS NOT NULL
                        """)
                .param("dish", dishId)
                .param("kitchen", workspace.kitchenId())
                .query(String.class)
                .optional()
                .orElse(null);
        if (recipeVersionId != null) {
            refresh(recipeVersionId);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void refresh(String recipeVersionId) {
        RecipeContext context = jdbc.sql("""
                        SELECT recipe.dish_id
                        FROM recipe_versions recipe
                        JOIN dishes dish
                          ON dish.id = recipe.dish_id
                         AND dish.kitchen_id = recipe.kitchen_id
                        WHERE recipe.id = :recipe
                          AND recipe.kitchen_id = :kitchen
                          AND recipe.active = TRUE
                          AND dish.active = TRUE
                          AND dish.active_recipe_version_id = recipe.id
                        FOR UPDATE
                        """)
                .param("recipe", recipeVersionId)
                .param("kitchen", workspace.kitchenId())
                .query((rs, row) -> new RecipeContext(
                        rs.getString("dish_id")
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
                        SELECT id, status, theme
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
                        rs.getString("status"),
                        rs.getString("theme")
                ))
                .optional()
                .orElse(null);

        if (existing != null && "ACTIVE".equals(existing.status())) {
            updateFixedExperimentCounts(existing, evidence);
            return;
        }
        if (score.count() < FEEDBACK_THRESHOLD) {
            if (existing != null) {
                cancelProposal(existing, evidence);
            }
            return;
        }
        if (existing != null) {
            updateProposal(existing, score, evidence.size());
            return;
        }

        Instant now = Instant.now();
        String id = "EXP-" + UUID.randomUUID();
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

    private void updateFixedExperimentCounts(
            ExistingExperiment existing,
            List<FeedbackEvidence> evidence
    ) {
        int themeCount = countForTheme(existing.theme(), evidence);
        Instant now = Instant.now();
        jdbc.sql("""
                        UPDATE recipe_experiments
                        SET theme_count = :themeCount,
                            feedback_count = :feedbackCount,
                            updated_at = :now,
                            version = version + 1
                        WHERE id = :id
                          AND kitchen_id = :kitchen
                          AND (theme_count <> :themeCount
                               OR feedback_count <> :feedbackCount)
                        """)
                .param("themeCount", themeCount)
                .param("feedbackCount", evidence.size())
                .param("now", JdbcTimestamp.utc(now))
                .param("id", existing.id())
                .param("kitchen", workspace.kitchenId())
                .update();
    }

    private void cancelProposal(
            ExistingExperiment existing,
            List<FeedbackEvidence> evidence
    ) {
        Instant now = Instant.now();
        jdbc.sql("""
                        UPDATE recipe_experiments
                        SET status = 'CANCELLED',
                            theme_count = :themeCount,
                            feedback_count = :feedbackCount,
                            updated_at = :now,
                            completed_at = :now,
                            decision_reason =
                              'Feedback evidence fell below proposal threshold.',
                            version = version + 1
                        WHERE id = :id
                          AND kitchen_id = :kitchen
                          AND status = 'PROPOSED'
                        """)
                .param("themeCount", countForTheme(existing.theme(), evidence))
                .param("feedbackCount", evidence.size())
                .param("now", JdbcTimestamp.utc(now))
                .param("id", existing.id())
                .param("kitchen", workspace.kitchenId())
                .update();
    }

    private void updateProposal(
            ExistingExperiment existing,
            ThemeScore score,
            int feedbackCount
    ) {
        Instant now = Instant.now();
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
                          AND (theme <> :theme
                               OR theme_count <> :themeCount
                               OR feedback_count <> :feedbackCount
                               OR metric_name <> :metric
                               OR current_value <> 0
                               OR proposed_value <> 1)
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
            int count = count(rule, evidence);
            if (count > bestCount) {
                best = rule;
                bestCount = count;
            }
        }
        return new ThemeScore(best, bestCount);
    }

    private static int countForTheme(
            String theme,
            List<FeedbackEvidence> evidence
    ) {
        return THEMES.stream()
                .filter(rule -> rule.theme().equals(theme))
                .findFirst()
                .map(rule -> count(rule, evidence))
                .orElse(0);
    }

    private static int count(
            ThemeRule rule,
            List<FeedbackEvidence> evidence
    ) {
        return (int) evidence.stream().filter(rule::matches).count();
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

    private record RecipeContext(String dishId) {
    }

    private record ExistingExperiment(
            String id,
            String status,
            String theme
    ) {
    }
}
