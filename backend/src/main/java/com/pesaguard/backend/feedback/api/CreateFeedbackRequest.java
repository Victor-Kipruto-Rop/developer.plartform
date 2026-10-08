package com.pesaguard.backend.feedback.api;

import java.util.UUID;

import com.pesaguard.backend.feedback.domain.FeedbackType;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateFeedbackRequest(
        @NotNull FeedbackType type,
        @NotBlank @Size(max = 120) String title,
        @NotBlank @Size(max = 8000) String description,
        UUID projectId,
        UUID environmentId,
        @Valid FeedbackContextRequest context) {
}
