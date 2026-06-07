package com.termproject.mentalhealth.service.llmcache;

import java.util.List;

public record EmbeddingResult(
        List<Double> vector,
        String model
) {
}
