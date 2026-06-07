package com.termproject.mentalhealth.repository;

import com.termproject.mentalhealth.domain.ChatMessage;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    List<ChatMessage> findTop20BySessionIdOrderByCreatedAtDesc(Long sessionId);
}
