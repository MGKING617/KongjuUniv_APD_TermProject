package com.termproject.mentalhealth.service.llmcache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SemanticLlmCacheServiceTest {
    @Test
    void sameGeneralGuidanceQuestionHitsOnSecondLookup() {
        FakeCacheStore store = new FakeCacheStore();
        SemanticLlmCacheService service = newService(store);

        SemanticCacheLookupResult first = service.lookup(
                LlmPurpose.GENERAL_GUIDANCE,
                "LOW 단계는 무슨 뜻이야?",
                "friendly",
                "openrouter/free",
                "v1"
        );
        assertThat(first.cacheHit()).isFalse();

        service.store(first, LlmPurpose.GENERAL_GUIDANCE, "일반 안내 응답", "friendly", "openrouter/free", "v1");

        SemanticCacheLookupResult second = service.lookup(
                LlmPurpose.GENERAL_GUIDANCE,
                "LOW 단계는 무슨 뜻이야?",
                "friendly",
                "openrouter/free",
                "v1"
        );
        assertThat(second.cacheHit()).isTrue();
        assertThat(second.responseText()).contains("일반 안내 응답");
    }

    @Test
    void similarKoreanFaqQuestionCanHit() {
        FakeCacheStore store = new FakeCacheStore();
        SemanticLlmCacheService service = newService(store);

        SemanticCacheLookupResult first = service.lookup(
                LlmPurpose.FAQ,
                "이 앱은 우울 관련 참고 서비스야?",
                "polite",
                "openrouter/free",
                "v1"
        );
        service.store(first, LlmPurpose.FAQ, "FAQ 응답", "polite", "openrouter/free", "v1");

        SemanticCacheLookupResult second = service.lookup(
                LlmPurpose.FAQ,
                "이 서비스는 우울 관련 참고 지표를 보여주는 거야?",
                "polite",
                "openrouter/free",
                "v1"
        );
        assertThat(second.cacheHit()).isTrue();
    }

    @Test
    void unrelatedGeneralQuestionMisses() {
        FakeCacheStore store = new FakeCacheStore();
        SemanticLlmCacheService service = newService(store);

        SemanticCacheLookupResult first = service.lookup(
                LlmPurpose.GENERAL_GUIDANCE,
                "결과는 어떻게 해석하면 돼?",
                "friendly",
                "openrouter/free",
                "v1"
        );
        service.store(first, LlmPurpose.GENERAL_GUIDANCE, "결과 안내 응답", "friendly", "openrouter/free", "v1");

        SemanticCacheLookupResult second = service.lookup(
                LlmPurpose.GENERAL_GUIDANCE,
                "개인정보 정책은 어디서 확인해?",
                "friendly",
                "openrouter/free",
                "v1"
        );
        assertThat(second.cacheHit()).isFalse();
    }

    @Test
    void scoringSurveyAndSummaryPurposesAlwaysSkipSemanticCache() {
        SemanticLlmCacheService service = newService(new FakeCacheStore());

        assertThat(service.lookup(LlmPurpose.LIKERT_SCORING, "응 조금", "polite", "openrouter/free", "v1").skipReason())
                .isEqualTo("purpose-not-cacheable");
        assertThat(service.lookup(LlmPurpose.CONVERSATIONAL_SURVEY_TURN, "응 조금", "polite", "openrouter/free", "v1").skipReason())
                .isEqualTo("purpose-not-cacheable");
        assertThat(service.lookup(LlmPurpose.RESULT_SUMMARY, "summary", "polite", "openrouter/free", "v1").skipReason())
                .isEqualTo("purpose-not-cacheable");
    }

    @Test
    void crisisSignalsSkipLookup() {
        SemanticLlmCacheService service = newService(new FakeCacheStore());

        SemanticCacheLookupResult result = service.lookup(
                LlmPurpose.GENERAL_GUIDANCE,
                "죽고 싶다",
                "polite",
                "openrouter/free",
                "v1"
        );

        assertThat(result.cacheHit()).isFalse();
        assertThat(result.skipReason()).isEqualTo("input-filtered");
    }

    @Test
    void cacheStoreFailureDoesNotThrow() {
        SemanticLlmCacheService service = newService(new FailingCacheStore());

        SemanticCacheLookupResult result = service.lookup(
                LlmPurpose.GENERAL_GUIDANCE,
                "LOW 단계는 무슨 뜻이야?",
                "friendly",
                "openrouter/free",
                "v1"
        );

        assertThat(result.cacheHit()).isFalse();
        assertThat(result.skipReason()).isEqualTo("cache-error");
    }

    private SemanticLlmCacheService newService(SemanticCacheStore store) {
        return new SemanticLlmCacheService(
                true,
                "GENERAL_GUIDANCE,FAQ",
                "redis",
                "test-cache",
                86400,
                0.92,
                "test-embedding",
                800,
                200,
                new FakeEmbeddingProvider(),
                store
        );
    }

    private static class FakeEmbeddingProvider implements SemanticEmbeddingProvider {
        @Override
        public Optional<EmbeddingResult> embed(String normalizedInput, String embeddingModel) {
            String value = normalizedInput.replace(" ", "");
            if (value.contains("LOW") || value.contains("결과") || value.contains("해석")) {
                return Optional.of(new EmbeddingResult(List.of(1.0, 0.0, 0.0), embeddingModel));
            }
            if (value.contains("우울관련") || value.contains("참고")) {
                return Optional.of(new EmbeddingResult(List.of(0.0, 1.0, 0.0), embeddingModel));
            }
            if (value.contains("개인정보")) {
                return Optional.of(new EmbeddingResult(List.of(0.0, 0.0, 1.0), embeddingModel));
            }
            return Optional.of(new EmbeddingResult(List.of(0.0, 0.5, 0.5), embeddingModel));
        }
    }

    private static class FakeCacheStore implements SemanticCacheStore {
        private final List<CachedSemanticEntry> entries = new ArrayList<>();

        @Override
        public List<CachedSemanticEntry> findEntries(String namespaceKey, int maxEntries) {
            return entries.stream()
                    .filter(entry -> entry.key().startsWith(namespaceKey + ":"))
                    .limit(maxEntries)
                    .toList();
        }

        @Override
        public void save(String namespaceKey, SemanticCacheEntry entry, Duration ttl) {
            entries.add(new CachedSemanticEntry(namespaceKey + ":" + entries.size(), entry));
        }

        @Override
        public void recordHit(CachedSemanticEntry cachedEntry, Duration ttl) {
        }
    }

    private static class FailingCacheStore implements SemanticCacheStore {
        @Override
        public List<CachedSemanticEntry> findEntries(String namespaceKey, int maxEntries) {
            throw new RuntimeException("Redis unavailable");
        }

        @Override
        public void save(String namespaceKey, SemanticCacheEntry entry, Duration ttl) {
            throw new RuntimeException("Redis unavailable");
        }

        @Override
        public void recordHit(CachedSemanticEntry cachedEntry, Duration ttl) {
            throw new RuntimeException("Redis unavailable");
        }
    }
}
