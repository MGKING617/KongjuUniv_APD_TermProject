package com.termproject.mentalhealth.dto;

public record LikertScoreResponse(
        int score,
        double confidence,
        String label,
        String rationale,
        boolean validAnswer
) {
}
