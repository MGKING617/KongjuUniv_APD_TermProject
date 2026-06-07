package com.termproject.mentalhealth.repository;

import com.termproject.mentalhealth.domain.RiskEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RiskEventRepository extends JpaRepository<RiskEvent, Long> {
    List<RiskEvent> findTop100ByOrderByCreatedAtDesc();
}
