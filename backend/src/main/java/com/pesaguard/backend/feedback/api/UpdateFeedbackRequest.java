package com.pesaguard.backend.feedback.api;

import com.pesaguard.backend.feedback.domain.FeedbackPriority;
import com.pesaguard.backend.feedback.domain.FeedbackStatus;

public record UpdateFeedbackRequest(FeedbackStatus status, FeedbackPriority priority) {
}
