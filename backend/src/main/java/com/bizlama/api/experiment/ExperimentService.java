package com.bizlama.api.experiment;

import com.bizlama.api.store.OperationalRepository;
import org.springframework.stereotype.Service;

@Service
public class ExperimentService {

    private final OperationalRepository repository;

    public ExperimentService(OperationalRepository repository) {
        this.repository = repository;
    }

    public ExperimentResponse getExperiment(String reference) {
        return repository.experiment(reference);
    }

    public ExperimentResponse approve(String reference) {
        repository.approveExperiment(reference);
        return getExperiment(reference);
    }
}