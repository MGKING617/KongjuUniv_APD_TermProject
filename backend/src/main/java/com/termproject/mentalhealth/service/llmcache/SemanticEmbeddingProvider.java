package com.termproject.mentalhealth.service.llmcache;

import java.util.Optional;

public interface SemanticEmbeddingProvider {
    Optional<EmbeddingResult> embed(String normalizedInput, String embeddingModel);
}
