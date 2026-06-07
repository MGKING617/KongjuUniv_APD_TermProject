package com.termproject.mentalhealth.dto;

import com.termproject.mentalhealth.domain.ChatMessage;
import com.termproject.mentalhealth.domain.SenderType;
import java.time.LocalDateTime;

public record ChatMessageDto(Long id, SenderType sender, String content, LocalDateTime createdAt) {
    public static ChatMessageDto from(ChatMessage message) {
        return new ChatMessageDto(message.getId(), message.getSender(), message.getContent(), message.getCreatedAt());
    }
}
