package com.termproject.mentalhealth.repository;

import com.termproject.mentalhealth.domain.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {
}
