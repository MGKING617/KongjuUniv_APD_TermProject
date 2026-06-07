package com.termproject.mentalhealth.dto;

public record RegenerateChatResponse(
        ChatMessageDto botMessage,
        RiskEventResponse riskEvent
) {
}
