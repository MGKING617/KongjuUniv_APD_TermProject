package com.termproject.mentalhealth.dto;

public record DomainScoreResponse(
        String key,
        String label,
        double score,
        String description
) {
}
