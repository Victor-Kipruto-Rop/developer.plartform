package com.pesaguard.backend.feedback.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "feedback_comments")
public class FeedbackComment {

    @Id
    private UUID id;

    @Column(name = "feedback_id", nullable = false, updatable = false)
    private UUID feedbackId;

    @Column(name = "author_id", nullable = false, updatable = false)
    private UUID authorId;

    @Column(name = "author_display_name", nullable = false, length = 120, updatable = false)
    private String authorDisplayName;

    @Column(name = "author_role", nullable = false, length = 16, updatable = false)
    private String authorRole;

    @Column(name = "body", nullable = false, length = 4000, updatable = false)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 16, updatable = false)
    private FeedbackVisibility visibility;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected FeedbackComment() {
    }

    private FeedbackComment(UUID feedbackId, UUID authorId, String authorDisplayName, String authorRole,
            String body, FeedbackVisibility visibility, Instant now) {
        id = UUID.randomUUID();
        this.feedbackId = feedbackId;
        this.authorId = authorId;
        this.authorDisplayName = authorDisplayName;
        this.authorRole = authorRole;
        this.body = body;
        this.visibility = visibility;
        createdAt = now;
    }

    public static FeedbackComment create(UUID feedbackId, UUID authorId, String authorDisplayName,
            String authorRole, String body, FeedbackVisibility visibility, Instant now) {
        return new FeedbackComment(feedbackId, authorId, authorDisplayName, authorRole,
                body, visibility, now);
    }

    public UUID getId() { return id; }
    public UUID getFeedbackId() { return feedbackId; }
    public UUID getAuthorId() { return authorId; }
    public String getAuthorDisplayName() { return authorDisplayName; }
    public String getAuthorRole() { return authorRole; }
    public String getBody() { return body; }
    public FeedbackVisibility getVisibility() { return visibility; }
    public Instant getCreatedAt() { return createdAt; }
}
