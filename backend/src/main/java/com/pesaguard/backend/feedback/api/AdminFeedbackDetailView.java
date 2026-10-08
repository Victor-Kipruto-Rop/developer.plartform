package com.pesaguard.backend.feedback.api;

import java.util.List;

public record AdminFeedbackDetailView(
        AdminFeedbackItemView item,
        String pageUrl,
        String route,
        String browser,
        String operatingSystem,
        String applicationVersion,
        String frontendVersion,
        String requestId,
        String correlationId,
        List<AdminFeedbackCommentView> comments,
        List<FeedbackActivityView> activity) {
}
