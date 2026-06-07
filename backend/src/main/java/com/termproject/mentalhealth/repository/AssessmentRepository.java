package com.termproject.mentalhealth.repository;

import com.termproject.mentalhealth.domain.Assessment;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssessmentRepository extends JpaRepository<Assessment, Long> {
    List<Assessment> findByUserIdOrderByCreatedAtDesc(Long userId);
    List<Assessment> findTop100ByOrderByCreatedAtDesc();
}
