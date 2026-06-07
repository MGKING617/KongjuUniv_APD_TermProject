package com.termproject.mentalhealth.dto;

public record ChatMessageResponse(ChatMessageDto userMessage, ChatMessageDto botMessage, RiskEventResponse riskEvent) {
}
