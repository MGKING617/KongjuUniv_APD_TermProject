package com.termproject.mentalhealth.service.llmcache;

import java.util.Optional;

public record SemanticCacheLookupResult(
        Optional<String> responseText,
        boolean cacheEnabled,
        boolean cacheHit,
        String skipReason,
        long embeddingMs,
        long lookupMs,
        double similarityScore,
        SemanticCacheProbe probe
) {
    public static SemanticCacheLookupResult skipped(boolean cacheEnabled, String reason) {
        return new SemanticCacheLookupResult(
                Optional.empty(),
                cacheEnabled,
                false,
                reason,
                0,
                0,
                0.0,
                SemanticCacheProbe.skipped(reason)
        );
    }
}
