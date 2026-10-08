package com.pesaguard.backend.feedback.api;

import java.time.Instant;

import com.pesaguard.backend.feedback.domain.FeedbackComment;

public record AdminFeedbackCommentView(
        String authorRole,
        String authorName,
        String visibility,
        String body,
        Instant createdAt) {

    public static AdminFeedbackCommentView from(FeedbackComment comment) {
        return new AdminFeedbackCommentView(comment.getAuthorRole(),
                comment.getAuthorDisplayName(), comment.getVisibility().name(),
                comment.getBody(), comment.getCreatedAt());
    }
}
