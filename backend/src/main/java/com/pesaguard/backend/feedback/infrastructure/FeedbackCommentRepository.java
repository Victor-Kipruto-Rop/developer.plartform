package com.pesaguard.backend.feedback.infrastructure;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pesaguard.backend.feedback.domain.FeedbackComment;
import com.pesaguard.backend.feedback.domain.FeedbackVisibility;

public interface FeedbackCommentRepository extends JpaRepository<FeedbackComment, UUID> {

    List<FeedbackComment> findByFeedbackIdAndVisibilityOrderByCreatedAtAsc(
            UUID feedbackId, FeedbackVisibility visibility);

    List<FeedbackComment> findByFeedbackIdOrderByCreatedAtAsc(UUID feedbackId);

    long countByFeedbackIdAndVisibility(UUID feedbackId, FeedbackVisibility visibility);
}
