package com.termproject.mentalhealth.service.llmcache;

import java.time.Instant;
import java.util.List;

public record SemanticCacheEntry(
        String normalizedInput,
        List<Double> embedding,
        String responseText,
        LlmPurpose purpose,
        String llmModel,
        String promptVersion,
        String embeddingModel,
        String tone,
        Instant createdAt,
        Instant expiresAt,
        long hitCount,
        Instant lastAccessedAt
) {
    public SemanticCacheEntry recordHit(Instant accessedAt) {
        return new SemanticCacheEntry(
                normalizedInput,
                embedding,
                responseText,
                purpose,
                llmModel,
                promptVersion,
                embeddingModel,
                tone,
                createdAt,
                expiresAt,
                hitCount + 1,
                accessedAt
        );
    }
}
