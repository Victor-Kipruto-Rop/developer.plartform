package com.pesaguard.backend.feedback.api;

import java.util.List;

public record AdminFeedbackPageView(
        List<AdminFeedbackItemView> items,
        int page,
        int pageSize,
        long totalItems,
        int totalPages) {
}
