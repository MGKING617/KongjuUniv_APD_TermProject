package com.termproject.mentalhealth.dto;

import jakarta.validation.constraints.NotBlank;

public record StartSurveyChatRequest(
        @NotBlank String questionTitle,
        int questionIndex,
        int totalQuestions,
        String tone
) {
}
