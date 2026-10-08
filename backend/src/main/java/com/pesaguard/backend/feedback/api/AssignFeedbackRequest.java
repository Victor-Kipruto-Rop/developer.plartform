package com.pesaguard.backend.feedback.api;

import com.pesaguard.backend.feedback.domain.FeedbackAssignmentTeam;

import jakarta.validation.constraints.NotNull;

public record AssignFeedbackRequest(@NotNull FeedbackAssignmentTeam team) {
}
