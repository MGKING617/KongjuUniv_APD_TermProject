package com.termproject.mentalhealth.repository;

import com.termproject.mentalhealth.domain.ConsentLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConsentLogRepository extends JpaRepository<ConsentLog, Long> {
}
