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
@Table(name = "feedback")
public class Feedback {

    @Id
    private UUID id;

    @Column(name = "public_reference", nullable = false, unique = true, updatable = false, length = 24)
    private String publicReference;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "requester_display_name", nullable = false, length = 120, updatable = false)
    private String requesterDisplayName;

    @Column(name = "requester_email", nullable = false, length = 320, updatable = false)
    private String requesterEmail;

    @Column(name = "project_id", updatable = false)
    private UUID projectId;

    @Column(name = "environment_id", updatable = false)
    private UUID environmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32, updatable = false)
    private FeedbackType type;

    @Column(name = "title", nullable = false, length = 120)
    private String title;

    @Column(name = "description", nullable = false, length = 8000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 16)
    private FeedbackPriority priority;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private FeedbackStatus status;

    @Column(name = "page_url", length = 1000, updatable = false)
    private String pageUrl;

    @Column(name = "route", length = 500, updatable = false)
    private String route;

    @Column(name = "browser", length = 200, updatable = false)
    private String browser;

    @Column(name = "operating_system", length = 200, updatable = false)
    private String operatingSystem;

    @Column(name = "application_version", length = 100, updatable = false)
    private String applicationVersion;

    @Column(name = "frontend_version", length = 100, updatable = false)
    private String frontendVersion;

    @Column(name = "request_id", length = 100, updatable = false)
    private String requestId;

    @Column(name = "correlation_id", length = 100, updatable = false)
    private String correlationId;

    @Column(name = "assigned_team", length = 32)
    private String assignedTeam;

    @Column(name = "assigned_operator_id")
    private UUID assignedOperatorId;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Feedback() {
    }

    private Feedback(String publicReference, UUID organizationId, UUID userId,
            String requesterDisplayName, String requesterEmail, UUID projectId,
            UUID environmentId, FeedbackType type, String title, String description,
            String pageUrl, String route, String browser, String operatingSystem,
            String applicationVersion, String frontendVersion, String requestId,
            String correlationId, Instant now) {
        this.id = UUID.randomUUID();
        this.publicReference = publicReference;
        this.organizationId = organizationId;
        this.userId = userId;
        this.requesterDisplayName = requesterDisplayName;
        this.requesterEmail = requesterEmail;
        this.projectId = projectId;
        this.environmentId = environmentId;
        this.type = type;
        this.title = title;
        this.description = description;
        this.priority = FeedbackPriority.NORMAL;
        this.status = FeedbackStatus.NEW;
        this.pageUrl = pageUrl;
        this.route = route;
        this.browser = browser;
        this.operatingSystem = operatingSystem;
        this.applicationVersion = applicationVersion;
        this.frontendVersion = frontendVersion;
        this.requestId = requestId;
        this.correlationId = correlationId;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static Feedback create(String publicReference, UUID organizationId, UUID userId,
            String requesterDisplayName, String requesterEmail,
            UUID projectId, UUID environmentId, FeedbackType type, String title, String description,
            String pageUrl, String route, String browser, String operatingSystem,
            String applicationVersion, String frontendVersion, String requestId,
            String correlationId, Instant now) {
        return new Feedback(publicReference, organizationId, userId, requesterDisplayName,
                requesterEmail, projectId, environmentId,
                type, title, description, pageUrl, route, browser, operatingSystem,
                applicationVersion, frontendVersion, requestId, correlationId, now);
    }

    public void transition(FeedbackStatus next, Instant now) {
        if (!status.mayTransitionTo(next)) {
            throw new IllegalStateException("The feedback status transition is not allowed.");
        }
        status = next;
        if (next == FeedbackStatus.RESOLVED) {
            resolvedAt = now;
            closedAt = null;
        } else if (next == FeedbackStatus.CLOSED) {
            resolvedAt = null;
            closedAt = now;
        } else {
            resolvedAt = null;
            closedAt = null;
        }
        updatedAt = now;
    }

    public void reopen(Instant now) {
        if (status != FeedbackStatus.RESOLVED && status != FeedbackStatus.CLOSED) {
            throw new IllegalStateException("Only resolved or closed feedback can be reopened.");
        }
        status = FeedbackStatus.ACKNOWLEDGED;
        resolvedAt = null;
        closedAt = null;
        updatedAt = now;
    }

    public void setPriority(FeedbackPriority next, Instant now) {
        if (next == null) {
            throw new IllegalArgumentException("Priority is required.");
        }
        priority = next;
        updatedAt = now;
    }

    public void assign(FeedbackAssignmentTeam team, UUID operatorId, Instant now) {
        assignedTeam = team == null ? null : team.name();
        assignedOperatorId = operatorId;
        updatedAt = now;
    }

    public UUID getId() { return id; }
    public String getPublicReference() { return publicReference; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getUserId() { return userId; }
    public String getRequesterDisplayName() { return requesterDisplayName; }
    public String getRequesterEmail() { return requesterEmail; }
    public UUID getProjectId() { return projectId; }
    public UUID getEnvironmentId() { return environmentId; }
    public FeedbackType getType() { return type; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public FeedbackPriority getPriority() { return priority; }
    public FeedbackStatus getStatus() { return status; }
    public String getPageUrl() { return pageUrl; }
    public String getRoute() { return route; }
    public String getBrowser() { return browser; }
    public String getOperatingSystem() { return operatingSystem; }
    public String getApplicationVersion() { return applicationVersion; }
    public String getFrontendVersion() { return frontendVersion; }
    public String getRequestId() { return requestId; }
    public String getCorrelationId() { return correlationId; }
    public String getAssignedTeam() { return assignedTeam; }
    public UUID getAssignedOperatorId() { return assignedOperatorId; }
    public Instant getResolvedAt() { return resolvedAt; }
    public Instant getClosedAt() { return closedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
