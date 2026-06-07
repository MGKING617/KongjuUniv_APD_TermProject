package com.termproject.mentalhealth.dto;

import java.util.List;

public record AiSummaryRequest(
        double finalScore,
        int phqLikeScore,
        double mlRiskPercent,
        String riskLevel,
        String tone,
        List<String> detectedSignals,
        List<DomainScoreResponse> domainScores,
        List<FactorContributionResponse> factorContributions,
        List<GlobalImportanceResponse> globalImportance
) {
}
