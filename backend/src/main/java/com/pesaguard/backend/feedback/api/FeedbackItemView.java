package com.pesaguard.backend.feedback.api;

import java.time.Instant;
import java.util.UUID;

import com.pesaguard.backend.feedback.domain.Feedback;

public record FeedbackItemView(
        String reference,
        String type,
        String title,
        String description,
        String priority,
        String status,
        Instant createdAt,
        Instant updatedAt,
        UUID projectId,
        String projectName,
        UUID environmentId,
        String environmentName,
        long commentCount) {

    public static FeedbackItemView from(Feedback feedback, String projectName,
            String environmentName, long commentCount) {
        return new FeedbackItemView(feedback.getPublicReference(), feedback.getType().name(),
                feedback.getTitle(), feedback.getDescription(), feedback.getPriority().name(),
                feedback.getStatus().name(), feedback.getCreatedAt(), feedback.getUpdatedAt(),
                feedback.getProjectId(), projectName, feedback.getEnvironmentId(),
                environmentName, commentCount);
    }
}
