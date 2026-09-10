package com.bizlama.api.catalog;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.bizlama.api.store.OperationalRepository;

@Service
public class ProductNormalizer {

    private final OperationalRepository repository;

    public ProductNormalizer(OperationalRepository repository) {
        this.repository = repository;
    }

    public Optional<Match> normalize(String rawName) {
        String value = Normalizer.normalize(rawName, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();

        return repository.aliases()
                .stream()
                .filter(alias ->
                        value.equals(alias.alias()) ||
                        value.contains(alias.alias()))
                .max(Comparator.comparingInt(
                        alias -> alias.alias().length()))
                .map(alias -> new Match(
                        alias.ingredientId(),
                        alias.canonicalName(),
                        alias.confidence(),
                        alias.source()
                ));
    }

    public record Match(
            String ingredientId,
            String canonicalName,
            double confidence,
            String source
    ) {}
}