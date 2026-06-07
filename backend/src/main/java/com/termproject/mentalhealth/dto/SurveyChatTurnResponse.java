package com.termproject.mentalhealth.dto;

public record SurveyChatTurnResponse(
        ChatMessageDto userMessage,
        ChatMessageDto botMessage,
        LikertScoreResponse score,
        RiskEventResponse riskEvent,
        String nextQuestionKey,
        boolean completed
) {
}
