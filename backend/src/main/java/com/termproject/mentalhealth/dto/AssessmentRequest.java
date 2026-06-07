package com.termproject.mentalhealth.dto;

import jakarta.validation.constraints.NotNull;
import java.util.Map;

public record AssessmentRequest(
        @NotNull Long userId,
        @NotNull Map<String, Integer> answers,
        Map<String, Object> extraFeatures
) {
}
