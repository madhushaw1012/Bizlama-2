package com.bizlama.api.feedback;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.bizlama.api.domain.Feedback;
import com.bizlama.api.store.OperationalRepository;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {

    private final OperationalRepository store;

    public FeedbackController(OperationalRepository store) {
        this.store = store;
    }

    @GetMapping
    public List<Feedback> list(
            @RequestParam(required = false) String recipeId) {
        return store.feedback(recipeId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Feedback create(
            @Valid @RequestBody CreateFeedbackRequest request) {

        if (store.recipe(request.recipeId()).isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Recipe version not found."
            );
        }

        String id = "FB-" + UUID.randomUUID()
                .toString()
                .substring(0, 8);

        Feedback feedback = new Feedback(
                id,
                request.recipeId(),
                request.text(),
                request.rating(),
                java.time.LocalDate.now(),
                request.source()
        );

        return store.saveFeedback(feedback);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        store.deleteFeedback(id);
    }

    public record CreateFeedbackRequest(
            @NotBlank String recipeId,
            @NotBlank String text,
            int rating,
            @NotBlank String source
    ) {
    }
}