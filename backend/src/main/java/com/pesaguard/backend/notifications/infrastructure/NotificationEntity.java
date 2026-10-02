package com.pesaguard.backend.notifications.infrastructure;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A stored notification.
 *
 * <p>Per-channel state is held in the {@code deliveries} column as a compact
 * string. A child table would be the more conventional shape, but the state is
 * always read and written with the notification as a whole and is bounded to two
 * channels, so a second table would add a join and a consistency window without
 * buying anything.
 */
@Entity
@Table(name = "notifications")
public class NotificationEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 48)
    private com.pesaguard.backend.notifications.domain.NotificationType type;

    @Column(name = "subject", nullable = false, length = 200)
    private String subject;

    @Column(name = "body", nullable = false, length = 4000)
    private String body;

    /**
     * Per-channel state, e.g. {@code EMAIL=RETRY_SCHEDULED:2;IN_APP=DELIVERED:1}.
     */
    @Column(name = "deliveries", nullable = false, length = 500)
    private String deliveries;

    @Column(name = "read_at")
    private Instant readAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected NotificationEntity() {
    }

    public NotificationEntity(com.pesaguard.backend.notifications.domain.Notification record) {
        this.id = record.id();
        this.organizationId = record.organizationId();
        this.userId = record.userId();
        this.type = record.type();
        this.subject = record.subject();
        this.body = record.body();
        this.deliveries = encode(record);
    }

    private static String encode(
            com.pesaguard.backend.notifications.domain.Notification record) {
        StringBuilder encoded = new StringBuilder();
        for (var entry : record.deliveries().entrySet()) {
            if (encoded.length() > 0) {
                encoded.append(';');
            }
            var delivery = entry.getValue();
            encoded.append(entry.getKey().name())
                    .append('=')
                    .append(delivery.state().name())
                    .append(':')
                    .append(delivery.attempts());
            if (delivery.lastError() != null) {
                // Errors are stored so a failed delivery is explainable after the
                // fact. Separated by a space-free token to keep parsing trivial.
                encoded.append(':').append(sanitise(delivery.lastError()));
            }
        }
        return encoded.toString();
    }

    private static String sanitise(String error) {
        // Semicolons and separators would corrupt the encoding.
        return error.replace(';', ' ').replace('=', ' ');
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getUserId() { return userId; }
    public com.pesaguard.backend.notifications.domain.NotificationType getType() { return type; }
    public String getSubject() { return subject; }
    public String getBody() { return body; }
    public String getDeliveries() { return deliveries; }
    public Instant getReadAt() { return readAt; }
    public Instant getCreatedAt() { return createdAt; }
}