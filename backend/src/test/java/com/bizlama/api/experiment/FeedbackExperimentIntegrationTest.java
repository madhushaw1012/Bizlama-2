package com.bizlama.api.experiment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bizlama.api.feedback.FeedbackService;
import com.bizlama.api.store.OperationalRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@ActiveProfiles("memory")
@Transactional
class FeedbackExperimentIntegrationTest {

    private static final String KITCHEN = "kitchen-default";

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private FeedbackService feedback;

    @Autowired
    private ExperimentService experiments;

    @Autowired
    private OperationalRepository repository;

    private String dishId;
    private String recipeId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString();
        dishId = "feedback-dish-" + suffix;
        recipeId = "feedback-recipe-" + suffix;
        Instant createdAt = Instant.now().minusSeconds(60);

        jdbc.sql("""
                        INSERT INTO dishes
                        (id, name, price, active, created_at, kitchen_id)
                        VALUES
                        (:id, 'Feedback test dish', 12.00, TRUE, :createdAt, :kitchen)
                        """)
                .param("id", dishId)
                .param("createdAt", createdAt)
                .param("kitchen", KITCHEN)
                .update();
        jdbc.sql("""
                        INSERT INTO recipe_versions
                        (id, dish_id, version_number, change_reason, active,
                         active_dish_guard, created_at, effective_at,
                         yield_quantity, yield_unit, kitchen_id)
                        VALUES
                        (:id, :dish, 1, 'Feedback acceptance fixture', TRUE,
                         :dish, :createdAt, :createdAt, 1.000, 'each', :kitchen)
                        """)
                .param("id", recipeId)
                .param("dish", dishId)
                .param("createdAt", createdAt)
                .param("kitchen", KITCHEN)
                .update();
        jdbc.sql("""
                        UPDATE dishes
                        SET active_recipe_version_id = :recipe
                        WHERE id = :dish
                          AND kitchen_id = :kitchen
                        """)
                .param("recipe", recipeId)
                .param("dish", dishId)
                .param("kitchen", KITCHEN)
                .update();
    }

    @Test
    void qualifyingFeedbackCreatesAndApprovesExperimentWithoutChangingRecipe() {
        feedback.capture(recipeId, "The filling was too salty.", 3, "test");
        feedback.capture(recipeId, "This tasted salty today.", 3, "test");

        assertThat(experimentCount()).isZero();

        feedback.capture(recipeId, "The paneer is over salted.", 3, "test");

        ExperimentResponse proposed = repository.experiment(dishId);
        assertThat(proposed.theme()).isEqualTo("Too salty");
        assertThat(proposed.themeCount()).isEqualTo(3);
        assertThat(proposed.feedbackCount()).isEqualTo(3);
        assertThat(proposed.status()).isEqualTo(ExperimentStatus.PROPOSED);
        assertThat(proposed.approvedAt()).isNull();
        assertThat(activeRecipe()).isEqualTo(recipeId);
        assertThat(feedbackActivityCount()).isEqualTo(3);

        repository.approveExperiment(dishId, "owner@example.test");

        ExperimentResponse active = repository.experiment(dishId);
        assertThat(active.status()).isEqualTo(ExperimentStatus.ACTIVE);
        assertThat(active.approvedAt()).isNotNull();
        assertThat(activeRecipe()).isEqualTo(recipeId);
        assertThat(approvedBy()).isEqualTo("owner@example.test");

        repository.approveExperiment(dishId, "owner@example.test");

        assertThat(repository.experiment(dishId).approvedAt())
                .isEqualTo(active.approvedAt());
        assertThat(approvalActivityCount()).isEqualTo(1);
    }

    @Test
    void persistedSeedFeedbackCreatesANewMenuExperimentOnReconciliation() {
        ExperimentResponse proposed =
                experiments.getExperiment("demo-mango-lassi");

        assertThat(proposed.dish()).isEqualTo("Mango Lassi");
        assertThat(proposed.theme()).isEqualTo("Too sweet");
        assertThat(proposed.themeCount()).isEqualTo(3);
        assertThat(proposed.feedbackCount()).isEqualTo(5);
        assertThat(proposed.status()).isEqualTo(ExperimentStatus.PROPOSED);
        assertThat(activeRecipe("demo-mango-lassi"))
                .isEqualTo("demo-mango-lassi-v1");
    }

    @Test
    void deletingEvidenceWithdrawsOnlyAnUnapprovedProposal() {
        feedback.capture(recipeId, "The filling was too salty.", 3, "test");
        feedback.capture(recipeId, "This tasted salty today.", 3, "test");
        feedback.capture(recipeId, "The paneer is over salted.", 3, "test");

        String feedbackId = jdbc.sql("""
                        SELECT id FROM feedback
                        WHERE recipe_id = :recipe AND source = 'test'
                        ORDER BY id
                        LIMIT 1
                        """)
                .param("recipe", recipeId)
                .query(String.class)
                .single();
        feedback.delete(feedbackId);

        assertThatThrownBy(() -> experiments.getExperiment(dishId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Experiment not found");
        assertThat(jdbc.sql("""
                        SELECT status FROM recipe_experiments
                        WHERE dish_id = :dish
                        """)
                .param("dish", dishId)
                .query(String.class)
                .single()).isEqualTo("CANCELLED");
        assertThat(activeRecipe()).isEqualTo(recipeId);
    }

    @Test
    void activeExperimentKeepsItsApprovedThemeAsFeedbackChanges() {
        feedback.capture(recipeId, "The filling was too salty.", 3, "test");
        feedback.capture(recipeId, "This tasted salty today.", 3, "test");
        feedback.capture(recipeId, "The paneer is over salted.", 3, "test");
        repository.approveExperiment(dishId, "owner@example.test");

        feedback.capture(recipeId, "This is too sweet.", 4, "test");
        feedback.capture(recipeId, "There is too much sugar.", 4, "test");
        feedback.capture(recipeId, "The sauce is overly sweet.", 4, "test");
        feedback.capture(
                recipeId,
                "It was sweeter than expected.",
                4,
                "test"
        );

        ExperimentResponse active = repository.experiment(dishId);
        assertThat(active.status()).isEqualTo(ExperimentStatus.ACTIVE);
        assertThat(active.theme()).isEqualTo("Too salty");
        assertThat(active.themeCount()).isEqualTo(3);
        assertThat(active.feedbackCount()).isEqualTo(7);
        assertThat(activeRecipe()).isEqualTo(recipeId);
    }

    @Test
    void feedbackRejectsRecipeThatIsNotTheDishActiveVersion() {
        String inactive = recipeId + "-inactive";
        jdbc.sql("""
                        INSERT INTO recipe_versions
                        (id, dish_id, version_number, change_reason, active,
                         created_at, yield_quantity, yield_unit, kitchen_id)
                        VALUES
                        (:id, :dish, 2, 'Inactive candidate', FALSE,
                         :createdAt, 1.000, 'each', :kitchen)
                        """)
                .param("id", inactive)
                .param("dish", dishId)
                .param("createdAt", Instant.now())
                .param("kitchen", KITCHEN)
                .update();

        assertThatThrownBy(() ->
                feedback.capture(inactive, "Too salty.", 3, "test"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("active recipe version");
        assertThat(jdbc.sql("""
                        SELECT COUNT(*) FROM feedback WHERE recipe_id = :recipe
                        """)
                .param("recipe", inactive)
                .query(Long.class)
                .single()).isZero();
    }

    private long experimentCount() {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM recipe_experiments
                        WHERE dish_id = :dish AND kitchen_id = :kitchen
                        """)
                .param("dish", dishId)
                .param("kitchen", KITCHEN)
                .query(Long.class)
                .single();
    }

    private String activeRecipe() {
        return jdbc.sql("SELECT active_recipe_version_id FROM dishes WHERE id = :dish")
                .param("dish", dishId)
                .query(String.class)
                .single();
    }

    private String activeRecipe(String selectedDishId) {
        return jdbc.sql("SELECT active_recipe_version_id FROM dishes WHERE id = :dish")
                .param("dish", selectedDishId)
                .query(String.class)
                .single();
    }

    private String approvedBy() {
        return jdbc.sql("""
                        SELECT approved_by FROM recipe_experiments
                        WHERE dish_id = :dish AND status = 'ACTIVE'
                        """)
                .param("dish", dishId)
                .query(String.class)
                .single();
    }

    private long feedbackActivityCount() {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM activity_events
                        WHERE event_type = 'Feedback'
                          AND description LIKE :recipe
                        """)
                .param("recipe", "%" + recipeId + "%")
                .query(Long.class)
                .single();
    }

    private long approvalActivityCount() {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM activity_events
                        WHERE event_type = 'Recipe experiment'
                        """)
                .query(Long.class)
                .single();
    }
}
