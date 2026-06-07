package com.termproject.mentalhealth.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record SurveyChatTurnRequest(
        @NotBlank String content,
        String questionKey,
        @NotBlank String questionTitle,
        String nextQuestionTitle,
        List<SurveyQuestionBrief> remainingQuestions,
        int questionIndex,
        int totalQuestions,
        String tone
) {
}
