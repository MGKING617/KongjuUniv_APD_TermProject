package com.termproject.mentalhealth.dto;

public record AiSummaryResponse(
        String summary,
        boolean fallbackUsed
) {
}
