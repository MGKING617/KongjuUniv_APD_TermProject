package com.termproject.mentalhealth.dto;

import com.termproject.mentalhealth.domain.Assessment;
import com.termproject.mentalhealth.domain.RiskLevel;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;

public record AssessmentResponse(
        Long id,
        Long userId,
        String userName,
        int phqLikeScore,
        double mlRiskPercent,
        double finalScore,
        RiskLevel riskLevel,
        List<String> detectedSignals,
        List<DomainScoreResponse> domainScores,
        List<FactorContributionResponse> factorContributions,
        List<GlobalImportanceResponse> globalImportance,
        String summary,
        LocalDateTime createdAt
) {
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final TypeReference<List<DomainScoreResponse>> DOMAIN_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<FactorContributionResponse>> FACTOR_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<GlobalImportanceResponse>> IMPORTANCE_TYPE = new TypeReference<>() {};

    public static AssessmentResponse from(Assessment assessment) {
        return new AssessmentResponse(
                assessment.getId(),
                assessment.getUser().getId(),
                assessment.getUser().getName(),
                assessment.getPhqLikeScore(),
                assessment.getMlRiskPercent(),
                assessment.getFinalScore(),
                assessment.getRiskLevel(),
                List.copyOf(assessment.getDetectedSignals()),
                readList(assessment.getDomainScoresJson(), DOMAIN_TYPE),
                readList(assessment.getFactorContributionsJson(), FACTOR_TYPE),
                readList(assessment.getGlobalImportanceJson(), IMPORTANCE_TYPE),
                assessment.getSummary(),
                assessment.getCreatedAt()
        );
    }

    private static <T> List<T> readList(String json, TypeReference<List<T>> type) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception ignored) {
            return List.of();
        }
    }
}
