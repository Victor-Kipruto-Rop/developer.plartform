package com.pesaguard.backend.feedback.api;

import jakarta.validation.constraints.Size;

public record FeedbackContextRequest(
        @Size(max = 1000) String pageUrl,
        @Size(max = 500) String route,
        @Size(max = 200) String browser,
        @Size(max = 200) String operatingSystem,
        @Size(max = 100) String applicationVersion,
        @Size(max = 100) String frontendVersion,
        @Size(max = 100) String requestId,
        @Size(max = 100) String correlationId) {
}
