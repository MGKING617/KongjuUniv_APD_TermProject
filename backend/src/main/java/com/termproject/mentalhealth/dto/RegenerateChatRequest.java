package com.termproject.mentalhealth.dto;

import jakarta.validation.constraints.NotBlank;

public record RegenerateChatRequest(@NotBlank String content, String tone) {
}
