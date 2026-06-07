package com.termproject.mentalhealth.service.llmcache;

public record CachedSemanticEntry(
        String key,
        SemanticCacheEntry entry
) {
}
