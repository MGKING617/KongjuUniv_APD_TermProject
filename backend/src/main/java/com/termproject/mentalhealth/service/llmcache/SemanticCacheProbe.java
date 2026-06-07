package com.termproject.mentalhealth.service.llmcache;

import java.util.List;

public record SemanticCacheProbe(
        boolean lookupAllowed,
        String skipReason,
        String namespaceKey,
        String normalizedInput,
        List<Double> embedding,
        String embeddingModel,
        long embeddingMs,
        long lookupMs,
        double bestSimilarity
) {
    public static SemanticCacheProbe skipped(String reason) {
        return new SemanticCacheProbe(false, reason, "", "", List.of(), "", 0, 0, 0.0);
    }
}
