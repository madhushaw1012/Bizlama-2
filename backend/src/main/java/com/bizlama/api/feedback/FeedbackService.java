package com.bizlama.api.feedback;

import com.bizlama.api.config.WorkspaceProperties;
import com.bizlama.api.domain.Dish;
import com.bizlama.api.domain.Feedback;
import com.bizlama.api.domain.RecipeVersion;
import com.bizlama.api.experiment.FeedbackExperimentService;
import com.bizlama.api.store.OperationalRepository;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Transactional application boundary for feedback, audit, and eligibility. */
@Service
public class FeedbackService {

    private final OperationalRepository repository;
    private final FeedbackExperimentService experiments;
    private final WorkspaceProperties workspace;

    public FeedbackService(
            OperationalRepository repository,
            FeedbackExperimentService experiments,
            WorkspaceProperties workspace
    ) {
        this.repository = repository;
        this.experiments = experiments;
        this.workspace = workspace;
    }

    @Transactional
    public Feedback capture(
            String recipeId,
            String text,
            int rating,
            String source
    ) {
        RecipeVersion recipe = repository.recipe(recipeId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Recipe version not found."
                ));
        Dish dish = repository.dish(recipe.dishId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Dish not found."
                ));
        if (!recipe.active()
                || !recipe.id().equals(dish.activeRecipeVersionId())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Feedback must target the dish's active recipe version."
            );
        }
        if (text == null || text.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Feedback text is required."
            );
        }
        if (rating < 1 || rating > 5) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Feedback rating must be between 1 and 5."
            );
        }

        Feedback feedback = new Feedback(
                "FB-" + UUID.randomUUID().toString().substring(0, 8),
                recipe.id(),
                text.trim(),
                rating,
                LocalDate.now(workspace.zoneId()),
                source == null || source.isBlank()
                        ? "owner-entry"
                        : source.trim()
        );
        Feedback saved = repository.saveFeedback(feedback);
        experiments.refresh(recipe.id());
        return saved;
    }

    @Transactional
    public void delete(String id) {
        Feedback existing = repository.feedbackById(id).orElse(null);
        if (existing == null) {
            return;
        }
        RecipeVersion recipe = repository.recipe(existing.recipeId())
                .orElse(null);

        repository.deleteFeedback(id);
        if (recipe != null) {
            experiments.reconcileDish(recipe.dishId());
        }
    }
}
