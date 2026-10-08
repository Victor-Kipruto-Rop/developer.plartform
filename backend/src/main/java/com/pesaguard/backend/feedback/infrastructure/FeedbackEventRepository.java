package com.pesaguard.backend.feedback.infrastructure;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pesaguard.backend.feedback.domain.FeedbackEvent;

public interface FeedbackEventRepository extends JpaRepository<FeedbackEvent, UUID> {
    List<FeedbackEvent> findByFeedbackIdOrderByCreatedAtAsc(UUID feedbackId);
}
