package com.pesaguard.backend.feedback.api;

import java.time.Instant;

import com.pesaguard.backend.feedback.domain.FeedbackEvent;

public record FeedbackActivityView(
        String actorType,
        String eventType,
        String oldValue,
        String newValue,
        Instant createdAt) {

    public static FeedbackActivityView from(FeedbackEvent event) {
        return new FeedbackActivityView(event.getActorType(), event.getEventType(),
                event.getOldValue(), event.getNewValue(), event.getCreatedAt());
    }
}
