package com.termproject.mentalhealth.api;

import com.termproject.mentalhealth.dto.AssessmentResponse;
import com.termproject.mentalhealth.dto.RiskEventResponse;
import com.termproject.mentalhealth.service.AdminService;
import com.termproject.mentalhealth.service.AssessmentService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final AssessmentService assessmentService;
    private final AdminService adminService;

    public AdminController(AssessmentService assessmentService, AdminService adminService) {
        this.assessmentService = assessmentService;
        this.adminService = adminService;
    }

    @GetMapping("/assessments")
    public List<AssessmentResponse> getAssessments() {
        return assessmentService.getRecentAssessments();
    }

    @GetMapping("/risk-events")
    public List<RiskEventResponse> getRiskEvents() {
        return adminService.getRecentRiskEvents();
    }
}
