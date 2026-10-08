package com.pesaguard.backend.feedback.api;

public record AdminFeedbackItemView(
        FeedbackItemView feedback,
        String organizationName,
        String requesterDisplayName,
        String requesterEmail,
        String assignedTeam) {
}
