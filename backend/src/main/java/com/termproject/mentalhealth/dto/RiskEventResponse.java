package com.termproject.mentalhealth.dto;

import com.termproject.mentalhealth.domain.RiskEvent;
import com.termproject.mentalhealth.domain.RiskSeverity;
import java.time.LocalDateTime;

public record RiskEventResponse(
        Long id,
        String riskType,
        RiskSeverity severity,
        String evidenceText,
        String actionTaken,
        LocalDateTime createdAt
) {
    public static RiskEventResponse from(RiskEvent event) {
        if (event == null) {
            return null;
        }
        return new RiskEventResponse(
                event.getId(),
                event.getRiskType(),
                event.getSeverity(),
                event.getEvidenceText(),
                event.getActionTaken(),
                event.getCreatedAt()
        );
    }
}
