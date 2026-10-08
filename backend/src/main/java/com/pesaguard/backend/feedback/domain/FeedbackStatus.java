package com.pesaguard.backend.feedback.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum FeedbackStatus {
    NEW,
    ACKNOWLEDGED,
    REVIEWING,
    PLANNED,
    IN_PROGRESS,
    RESOLVED,
    CLOSED,
    REJECTED;

    private static final Map<FeedbackStatus, Set<FeedbackStatus>> TRANSITIONS = Map.of(
            NEW, EnumSet.of(ACKNOWLEDGED, REVIEWING, RESOLVED, REJECTED),
            ACKNOWLEDGED, EnumSet.of(REVIEWING, RESOLVED, REJECTED),
            REVIEWING, EnumSet.of(PLANNED, IN_PROGRESS, RESOLVED, REJECTED),
            PLANNED, EnumSet.of(IN_PROGRESS, REVIEWING, RESOLVED, REJECTED),
            IN_PROGRESS, EnumSet.of(REVIEWING, RESOLVED, REJECTED),
            RESOLVED, EnumSet.of(CLOSED, ACKNOWLEDGED),
            CLOSED, EnumSet.of(ACKNOWLEDGED),
            REJECTED, EnumSet.of(ACKNOWLEDGED));

    public boolean mayTransitionTo(FeedbackStatus next) {
        return next != null && TRANSITIONS.getOrDefault(this, Set.of()).contains(next);
    }
}
