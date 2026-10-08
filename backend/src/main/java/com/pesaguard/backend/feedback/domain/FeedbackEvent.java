package com.pesaguard.backend.feedback.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "feedback_events")
public class FeedbackEvent {

    @Id
    private UUID id;

    @Column(name = "feedback_id", nullable = false, updatable = false)
    private UUID feedbackId;

    @Column(name = "actor_id", nullable = false, updatable = false)
    private UUID actorId;

    @Column(name = "actor_type", nullable = false, length = 16, updatable = false)
    private String actorType;

    @Column(name = "event_type", nullable = false, length = 40, updatable = false)
    private String eventType;

    @Column(name = "old_value", length = 200, updatable = false)
    private String oldValue;

    @Column(name = "new_value", length = 200, updatable = false)
    private String newValue;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", nullable = false, columnDefinition = "jsonb", updatable = false)
    private Map<String, String> metadata;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected FeedbackEvent() {
    }

    private FeedbackEvent(UUID feedbackId, UUID actorId, String actorType, String eventType,
            String oldValue, String newValue, Map<String, String> metadata, Instant now) {
        id = UUID.randomUUID();
        this.feedbackId = feedbackId;
        this.actorId = actorId;
        this.actorType = actorType;
        this.eventType = eventType;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        createdAt = now;
    }

    public static FeedbackEvent record(UUID feedbackId, UUID actorId, String actorType,
            String eventType, String oldValue, String newValue, Map<String, String> metadata, Instant now) {
        return new FeedbackEvent(feedbackId, actorId, actorType, eventType,
                oldValue, newValue, metadata, now);
    }

    public UUID getId() { return id; }
    public UUID getFeedbackId() { return feedbackId; }
    public UUID getActorId() { return actorId; }
    public String getActorType() { return actorType; }
    public String getEventType() { return eventType; }
    public String getOldValue() { return oldValue; }
    public String getNewValue() { return newValue; }
    public Map<String, String> getMetadata() { return metadata; }
    public Instant getCreatedAt() { return createdAt; }
}
