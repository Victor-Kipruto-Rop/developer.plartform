package com.pesaguard.backend.feedback.api;

import java.util.List;

public record FeedbackPageView(
        List<FeedbackItemView> items,
        int page,
        int pageSize,
        long totalItems,
        int totalPages) {
}
