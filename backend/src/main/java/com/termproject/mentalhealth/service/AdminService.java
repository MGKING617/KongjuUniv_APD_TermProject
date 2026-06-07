package com.termproject.mentalhealth.service;

import com.termproject.mentalhealth.dto.RiskEventResponse;
import com.termproject.mentalhealth.repository.RiskEventRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminService {
    private final RiskEventRepository riskEventRepository;

    public AdminService(RiskEventRepository riskEventRepository) {
        this.riskEventRepository = riskEventRepository;
    }

    @Transactional(readOnly = true)
    public List<RiskEventResponse> getRecentRiskEvents() {
        return riskEventRepository.findTop100ByOrderByCreatedAtDesc().stream()
                .map(RiskEventResponse::from)
                .toList();
    }
}
