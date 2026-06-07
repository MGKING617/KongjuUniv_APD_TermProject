package com.termproject.mentalhealth.api;

import com.termproject.mentalhealth.dto.AssessmentRequest;
import com.termproject.mentalhealth.dto.AssessmentResponse;
import com.termproject.mentalhealth.service.AssessmentService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/assessments")
public class AssessmentController {
    private final AssessmentService assessmentService;

    public AssessmentController(AssessmentService assessmentService) {
        this.assessmentService = assessmentService;
    }

    @PostMapping("/survey")
    public AssessmentResponse submitSurvey(@Valid @RequestBody AssessmentRequest request) {
        return assessmentService.submitSurvey(request);
    }

    @GetMapping("/users/{userId}")
    public List<AssessmentResponse> getUserAssessments(@PathVariable Long userId) {
        return assessmentService.getUserAssessments(userId);
    }
}
