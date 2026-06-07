package com.termproject.mentalhealth.dto;

import jakarta.validation.constraints.NotBlank;

public record SendChatMessageRequest(@NotBlank String content, String tone) {
}
