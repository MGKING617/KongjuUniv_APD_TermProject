package com.termproject.mentalhealth.service.llmcache;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class MlSemanticEmbeddingProvider implements SemanticEmbeddingProvider {
    private final RestClient client;

    public MlSemanticEmbeddingProvider(@Value("${app.ml-api-url}") String mlApiUrl) {
        this.client = RestClient.builder()
                .baseUrl(mlApiUrl)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<EmbeddingResult> embed(String normalizedInput, String embeddingModel) {
        try {
            Map<String, Object> response = client.post()
                    .uri("/semantic-cache/embed")
                    .body(Map.of(
                            "text", normalizedInput,
                            "model", embeddingModel
                    ))
                    .retrieve()
                    .body(Map.class);
            if (response == null || !(response.get("embedding") instanceof List<?> rawVector)) {
                return Optional.empty();
            }
            List<Double> vector = new ArrayList<>();
            for (Object value : rawVector) {
                if (value instanceof Number number) {
                    vector.add(number.doubleValue());
                }
            }
            if (vector.isEmpty()) {
                return Optional.empty();
            }
            String model = response.get("model") instanceof String value ? value : embeddingModel;
            return Optional.of(new EmbeddingResult(vector, model));
        } catch (RuntimeException error) {
            return Optional.empty();
        }
    }
}
