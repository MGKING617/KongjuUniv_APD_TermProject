package com.termproject.mentalhealth.service.llmcache;

import java.time.Duration;
import java.util.List;

public interface SemanticCacheStore {
    List<CachedSemanticEntry> findEntries(String namespaceKey, int maxEntries);

    void save(String namespaceKey, SemanticCacheEntry entry, Duration ttl);

    void recordHit(CachedSemanticEntry cachedEntry, Duration ttl);
}
