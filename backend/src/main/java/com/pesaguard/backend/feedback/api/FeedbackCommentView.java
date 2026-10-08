package com.pesaguard.backend.feedback.api;

import java.time.Instant;

import com.pesaguard.backend.feedback.domain.FeedbackComment;

public record FeedbackCommentView(
        String authorRole,
        String authorName,
        String body,
        Instant createdAt) {

    public static FeedbackCommentView from(FeedbackComment comment) {
        String author = comment.getAuthorRole().equals("OPERATOR")
                ? "PesaGuard team" : comment.getAuthorDisplayName();
        return new FeedbackCommentView(comment.getAuthorRole(), author,
                comment.getBody(), comment.getCreatedAt());
    }
}
