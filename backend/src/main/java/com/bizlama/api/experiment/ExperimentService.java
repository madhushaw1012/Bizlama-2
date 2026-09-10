package com.bizlama.api.experiment;

import com.bizlama.api.store.OperationalRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ExperimentService {

    private final OperationalRepository repository;
    private final FeedbackExperimentService feedbackExperiments;

    public ExperimentService(
            OperationalRepository repository,
            FeedbackExperimentService feedbackExperiments
    ) {
        this.repository = repository;
        this.feedbackExperiments = feedbackExperiments;
    }

    public ExperimentResponse getExperiment(String reference) {
        // Materialize deterministic proposals for feedback loaded outside the
        // live capture path before resolving the current experiment.
        feedbackExperiments.reconcileDish(reference);
        try {
            return repository.experiment(reference);
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Experiment not found for this dish."
            );
        }
    }

    public ExperimentResponse approve(String reference, String actor) {
        try {
            repository.approveExperiment(reference, actor);
        } catch (IllegalArgumentException error) {
            if ("Experiment not found".equals(error.getMessage())) {
                throw new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Experiment not found for this dish."
                );
            }
            throw error;
        } catch (IllegalStateException error) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    error.getMessage()
            );
        }
        return getExperiment(reference);
    }
}
