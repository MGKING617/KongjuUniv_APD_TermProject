package com.termproject.mentalhealth.dto;

import com.termproject.mentalhealth.domain.ChatSession;
import com.termproject.mentalhealth.domain.ChatSessionStatus;
import java.time.LocalDateTime;

public record ChatSessionResponse(Long id, ChatSessionStatus status, LocalDateTime startedAt) {
    public static ChatSessionResponse from(ChatSession session) {
        return new ChatSessionResponse(session.getId(), session.getStatus(), session.getStartedAt());
    }
}
