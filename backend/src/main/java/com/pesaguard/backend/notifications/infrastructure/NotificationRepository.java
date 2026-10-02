package com.pesaguard.backend.notifications.infrastructure;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<NotificationEntity, UUID> {

    /**
     * A user's notifications, newest first.
     *
     * <p>Always organization-scoped as well as user-scoped: a user id taken from a
     * path must not be the only guard, because it is the tenant boundary that
     * actually matters here.
     */
    List<NotificationEntity> findByOrganizationIdAndUserIdOrderByCreatedAtDesc(
            UUID organizationId, UUID userId);

    long countByOrganizationIdAndUserIdAndType(UUID organizationId, UUID userId,
            com.pesaguard.backend.notifications.domain.NotificationType type);
}