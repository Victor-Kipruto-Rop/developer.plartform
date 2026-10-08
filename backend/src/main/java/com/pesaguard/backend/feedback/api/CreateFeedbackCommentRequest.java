package com.pesaguard.backend.feedback.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateFeedbackCommentRequest(@NotBlank @Size(max = 4000) String body) {
}
