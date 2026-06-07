package com.termproject.mentalhealth.service.llmcache;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class SemanticLlmCacheService {
    private static final Pattern REPEATED_SPACE = Pattern.compile("\\s+");
    private static final Pattern EMAIL = Pattern.compile("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", Pattern.CASE_INSENSITIVE);
    private static final Pattern PHONE = Pattern.compile("(01[016789]|02|0[3-6][1-5])[-\\s]?\\d{3,4}[-\\s]?\\d{4}");
    private static final Pattern LONG_DIGITS = Pattern.compile("\\d{6,}");
    private static final Set<LlmPurpose> SEMANTIC_CACHE_PURPOSES = EnumSet.of(LlmPurpose.GENERAL_GUIDANCE, LlmPurpose.FAQ);

    private final boolean enabled;
    private final Set<LlmPurpose> allowedPurposes;
    private final String backend;
    private final String namespace;
    private final Duration ttl;
    private final double similarityThreshold;
    private final String embeddingModel;
    private final int maxInputChars;
    private final int maxScanEntries;
    private final SemanticEmbeddingProvider embeddingProvider;
    private final SemanticCacheStore cacheStore;

    public SemanticLlmCacheService(
            @Value("${app.semantic-cache.enabled:false}") boolean enabled,
            @Value("${app.semantic-cache.allowed-purposes:GENERAL_GUIDANCE,FAQ}") String allowedPurposes,
            @Value("${app.semantic-cache.backend:redis}") String backend,
            @Value("${app.semantic-cache.namespace:youth-depression-llm:semantic-cache:v1}") String namespace,
            @Value("${app.semantic-cache.ttl-seconds:86400}") long ttlSeconds,
            @Value("${app.semantic-cache.similarity-threshold:0.92}") double similarityThreshold,
            @Value("${app.semantic-cache.embedding-model:intfloat/multilingual-e5-small}") String embeddingModel,
            @Value("${app.semantic-cache.max-input-chars:800}") int maxInputChars,
            @Value("${app.semantic-cache.max-scan-entries:200}") int maxScanEntries,
            SemanticEmbeddingProvider embeddingProvider,
            SemanticCacheStore cacheStore
    ) {
        this.enabled = enabled;
        this.allowedPurposes = parseAllowedPurposes(allowedPurposes);
        this.backend = backend == null ? "redis" : backend.trim();
        this.namespace = namespace == null || namespace.isBlank()
                ? "youth-depression-llm:semantic-cache:v1"
                : namespace.trim();
        this.ttl = Duration.ofSeconds(Math.max(1, ttlSeconds));
        this.similarityThreshold = similarityThreshold;
        this.embeddingModel = embeddingModel == null || embeddingModel.isBlank()
                ? "intfloat/multilingual-e5-small"
                : embeddingModel.trim();
        this.maxInputChars = Math.max(1, maxInputChars);
        this.maxScanEntries = Math.max(1, maxScanEntries);
        this.embeddingProvider = embeddingProvider;
        this.cacheStore = cacheStore;
    }

    public SemanticCacheLookupResult lookup(
            LlmPurpose purpose,
            String input,
            String tone,
            String llmModel,
            String promptVersion
    ) {
        if (!enabled) {
            return SemanticCacheLookupResult.skipped(false, "disabled");
        }
        String purposeSkipReason = purposeSkipReason(purpose);
        if (purposeSkipReason != null) {
            return SemanticCacheLookupResult.skipped(true, purposeSkipReason);
        }
        if (!"redis".equalsIgnoreCase(backend)) {
            return SemanticCacheLookupResult.skipped(true, "unsupported-backend");
        }

        Optional<String> normalized = normalizeForCache(input);
        if (normalized.isEmpty()) {
            return SemanticCacheLookupResult.skipped(true, "input-filtered");
        }

        long embeddingStart = System.nanoTime();
        Optional<EmbeddingResult> embedding = embeddingProvider.embed(normalized.get(), embeddingModel);
        long embeddingMs = elapsedMs(embeddingStart);
        if (embedding.isEmpty()) {
            return new SemanticCacheLookupResult(
                    Optional.empty(),
                    true,
                    false,
                    "embedding-unavailable",
                    embeddingMs,
                    0,
                    0.0,
                    SemanticCacheProbe.skipped("embedding-unavailable")
            );
        }

        String namespaceKey = namespaceKey(purpose, tone, llmModel, promptVersion, embedding.get().model());
        long lookupStart = System.nanoTime();
        try {
            CachedSemanticEntry best = null;
            double bestSimilarity = 0.0;
            for (CachedSemanticEntry cachedEntry : cacheStore.findEntries(namespaceKey, maxScanEntries)) {
                double similarity = cosineSimilarity(embedding.get().vector(), cachedEntry.entry().embedding());
                if (similarity > bestSimilarity) {
                    bestSimilarity = similarity;
                    best = cachedEntry;
                }
            }
            long lookupMs = elapsedMs(lookupStart);
            SemanticCacheProbe probe = new SemanticCacheProbe(
                    true,
                    "",
                    namespaceKey,
                    normalized.get(),
                    embedding.get().vector(),
                    embedding.get().model(),
                    embeddingMs,
                    lookupMs,
                    bestSimilarity
            );
            if (best != null && bestSimilarity >= similarityThreshold) {
                cacheStore.recordHit(best, ttl);
                return new SemanticCacheLookupResult(
                        Optional.of(best.entry().responseText()),
                        true,
                        true,
                        "",
                        embeddingMs,
                        lookupMs,
                        bestSimilarity,
                        probe
                );
            }
            return new SemanticCacheLookupResult(
                    Optional.empty(),
                    true,
                    false,
                    "miss",
                    embeddingMs,
                    lookupMs,
                    bestSimilarity,
                    probe
            );
        } catch (RuntimeException error) {
            return new SemanticCacheLookupResult(
                    Optional.empty(),
                    true,
                    false,
                    "cache-error",
                    embeddingMs,
                    elapsedMs(lookupStart),
                    0.0,
                    SemanticCacheProbe.skipped("cache-error")
            );
        }
    }

    public void store(
            SemanticCacheLookupResult lookup,
            LlmPurpose purpose,
            String responseText,
            String tone,
            String llmModel,
            String promptVersion
    ) {
        if (!enabled || lookup.cacheHit() || !lookup.probe().lookupAllowed()) {
            return;
        }
        if (responseText == null || responseText.isBlank() || containsEmergencyGuidance(responseText)) {
            return;
        }
        try {
            Instant now = Instant.now();
            SemanticCacheEntry entry = new SemanticCacheEntry(
                    lookup.probe().normalizedInput(),
                    lookup.probe().embedding(),
                    responseText,
                    purpose,
                    llmModel,
                    promptVersion,
                    lookup.probe().embeddingModel(),
                    tone == null ? "" : tone,
                    now,
                    now.plus(ttl),
                    0,
                    now
            );
            cacheStore.save(lookup.probe().namespaceKey(), entry, ttl);
        } catch (RuntimeException ignored) {
            // Cache storage must never affect the user-facing LLM flow.
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    private String purposeSkipReason(LlmPurpose purpose) {
        if (purpose == null) {
            return "unknown-purpose";
        }
        if (!SEMANTIC_CACHE_PURPOSES.contains(purpose)) {
            return "purpose-not-cacheable";
        }
        if (!allowedPurposes.contains(purpose)) {
            return "purpose-disabled";
        }
        return null;
    }

    private Optional<String> normalizeForCache(String input) {
        if (input == null) {
            return Optional.empty();
        }
        String normalized = REPEATED_SPACE.matcher(input.trim()).replaceAll(" ");
        if (normalized.isBlank() || normalized.length() > maxInputChars) {
            return Optional.empty();
        }
        if (containsCrisisSignal(normalized) || containsSensitiveSignal(normalized) || isTimeDependent(normalized)) {
            return Optional.empty();
        }
        return Optional.of(normalized);
    }

    private boolean containsCrisisSignal(String value) {
        String compact = value.toLowerCase(Locale.ROOT).replace(" ", "");
        return containsAny(compact,
                "자해", "죽고싶", "죽고싶다", "삶을포기", "사라지고싶", "극단적", "끝내고싶",
                "해치고싶", "죽이고싶", "폭력을");
    }

    private boolean containsSensitiveSignal(String value) {
        String compact = value.toLowerCase(Locale.ROOT).replace(" ", "");
        return EMAIL.matcher(value).find()
                || PHONE.matcher(value).find()
                || LONG_DIGITS.matcher(value).find()
                || containsAny(compact,
                        "병력", "약물", "복용", "입원", "가족", "엄마", "아빠", "부모", "형제",
                        "개인사", "사건", "학교폭력", "성폭력", "주소", "주민번호", "계좌");
    }

    private boolean isTimeDependent(String value) {
        String compact = value.toLowerCase(Locale.ROOT).replace(" ", "");
        return containsAny(compact, "오늘", "내일", "어제", "지금", "현재", "최신", "실시간", "방금");
    }

    private boolean containsEmergencyGuidance(String responseText) {
        String compact = responseText.toLowerCase(Locale.ROOT).replace(" ", "");
        return containsAny(compact, "109", "119", "112", "자살예방상담", "즉시도움", "응급", "긴급한위험");
    }

    private boolean containsAny(String value, String... tokens) {
        for (String token : tokens) {
            if (value.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private String namespaceKey(
            LlmPurpose purpose,
            String tone,
            String llmModel,
            String promptVersion,
            String resolvedEmbeddingModel
    ) {
        return String.join(":",
                namespace,
                sanitizeKey(llmModel),
                sanitizeKey(promptVersion),
                sanitizeKey(resolvedEmbeddingModel),
                sanitizeKey(purpose.name()),
                sanitizeKey(tone == null ? "" : tone)
        );
    }

    private String sanitizeKey(String value) {
        if (value == null || value.isBlank()) {
            return "default";
        }
        return value.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private double cosineSimilarity(List<Double> left, List<Double> right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty() || left.size() != right.size()) {
            return 0.0;
        }
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int index = 0; index < left.size(); index++) {
            double leftValue = left.get(index);
            double rightValue = right.get(index);
            dot += leftValue * rightValue;
            leftNorm += leftValue * leftValue;
            rightNorm += rightValue * rightValue;
        }
        if (leftNorm <= 0 || rightNorm <= 0) {
            return 0.0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    private Set<LlmPurpose> parseAllowedPurposes(String raw) {
        Set<LlmPurpose> parsed = EnumSet.noneOf(LlmPurpose.class);
        if (raw == null || raw.isBlank()) {
            parsed.addAll(SEMANTIC_CACHE_PURPOSES);
            return parsed;
        }
        Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .forEach(value -> {
                    try {
                        parsed.add(LlmPurpose.valueOf(value));
                    } catch (IllegalArgumentException ignored) {
                        // Unknown configured purposes are ignored.
                    }
                });
        return parsed;
    }
}
