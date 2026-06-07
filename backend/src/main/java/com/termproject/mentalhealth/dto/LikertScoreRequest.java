package com.termproject.mentalhealth.dto;

import jakarta.validation.constraints.NotBlank;

public record LikertScoreRequest(
        @NotBlank String question,
        @NotBlank String answer
) {
}
