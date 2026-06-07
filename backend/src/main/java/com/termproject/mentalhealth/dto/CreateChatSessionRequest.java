package com.termproject.mentalhealth.dto;

import jakarta.validation.constraints.NotNull;

public record CreateChatSessionRequest(@NotNull Long userId) {
}
