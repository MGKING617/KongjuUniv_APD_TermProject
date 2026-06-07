package com.termproject.mentalhealth.dto;

public record FactorContributionResponse(
        String label,
        double impact,
        String direction,
        String explanation
) {
}
