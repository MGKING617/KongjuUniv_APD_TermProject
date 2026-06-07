package com.termproject.mentalhealth.service.llmcache;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class RedisSemanticCacheStore implements SemanticCacheStore {
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisSemanticCacheStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<CachedSemanticEntry> findEntries(String namespaceKey, int maxEntries) {
        List<String> keys = scanKeys(namespaceKey + ":*", maxEntries);
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        List<CachedSemanticEntry> entries = new ArrayList<>();
        for (String key : keys) {
            String value = redisTemplate.opsForValue().get(key);
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                SemanticCacheEntry entry = objectMapper.readValue(value, SemanticCacheEntry.class);
                if (entry.expiresAt() == null || entry.expiresAt().isAfter(Instant.now())) {
                    entries.add(new CachedSemanticEntry(key, entry));
                }
            } catch (Exception ignored) {
                // Corrupt cache entries should not affect the LLM path.
            }
        }
        return entries;
    }

    @Override
    public void save(String namespaceKey, SemanticCacheEntry entry, Duration ttl) {
        try {
            String key = namespaceKey + ":" + UUID.randomUUID();
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(entry), ttl);
        } catch (Exception ignored) {
            // Cache writes are best-effort only.
        }
    }

    @Override
    public void recordHit(CachedSemanticEntry cachedEntry, Duration ttl) {
        try {
            SemanticCacheEntry updated = cachedEntry.entry().recordHit(Instant.now());
            Duration remaining = ttlForExistingEntry(updated, ttl);
            if (!remaining.isNegative() && !remaining.isZero()) {
                redisTemplate.opsForValue().set(cachedEntry.key(), objectMapper.writeValueAsString(updated), remaining);
            }
        } catch (Exception ignored) {
            // Cache hit metadata is optional.
        }
    }

    private Duration ttlForExistingEntry(SemanticCacheEntry entry, Duration fallback) {
        if (entry.expiresAt() == null) {
            return fallback;
        }
        Duration remaining = Duration.between(Instant.now(), entry.expiresAt());
        return remaining.isNegative() || remaining.isZero() ? fallback : remaining;
    }

    private List<String> scanKeys(String pattern, int maxEntries) {
        return redisTemplate.execute((RedisCallback<List<String>>) connection -> scanKeys(connection, pattern, maxEntries));
    }

    private List<String> scanKeys(RedisConnection connection, String pattern, int maxEntries) {
        List<String> keys = new ArrayList<>();
        ScanOptions options = ScanOptions.scanOptions()
                .match(pattern)
                .count(Math.max(1, maxEntries))
                .build();
        try (Cursor<byte[]> cursor = connection.keyCommands().scan(options)) {
            while (cursor.hasNext() && keys.size() < maxEntries) {
                keys.add(new String(cursor.next(), StandardCharsets.UTF_8));
            }
        }
        return keys;
    }
}
