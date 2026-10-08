package com.pesaguard.backend.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.pesaguard.backend.common.exception.ResourceNotFoundException;
import com.pesaguard.backend.environment.infrastructure.ProjectEnvironmentRepository;
import com.pesaguard.backend.feedback.api.FeedbackContextRequest;
import com.pesaguard.backend.feedback.api.FeedbackItemView;
import com.pesaguard.backend.feedback.domain.FeedbackVisibility;
import com.pesaguard.backend.feedback.domain.Feedback;
import com.pesaguard.backend.feedback.application.FeedbackContextSanitizer;
import com.pesaguard.backend.feedback.application.FeedbackRateLimiter;
import com.pesaguard.backend.feedback.application.FeedbackService;
import com.pesaguard.backend.feedback.domain.Feedback;
import com.pesaguard.backend.feedback.domain.FeedbackStatus;
import com.pesaguard.backend.feedback.domain.FeedbackType;
import com.pesaguard.backend.feedback.infrastructure.FeedbackCommentRepository;
import com.pesaguard.backend.feedback.infrastructure.FeedbackEventRepository;
import com.pesaguard.backend.feedback.infrastructure.FeedbackRepository;
import com.pesaguard.backend.notifications.application.NotificationService;
import com.pesaguard.backend.platformadmin.domain.AuthenticatedOperator;
import com.pesaguard.backend.outbox.application.OutboxService;
import com.pesaguard.backend.organization.infrastructure.OrganizationRepository;
import com.pesaguard.backend.project.application.ProjectAccessScope;
import com.pesaguard.backend.project.infrastructure.ProjectRepository;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

import jakarta.persistence.EntityManager;

class FeedbackSecurityAndTransitionTest {

    @Test
    void contextRemovesQueryAndFragmentAndRejectsCredentialFields() {
        FeedbackContextSanitizer sanitizer = new FeedbackContextSanitizer();
        var safe = sanitizer.sanitize(new FeedbackContextRequest(
                "https://developers.example.test/suggestions?tab=all#top",
                "/suggestions?view=compact#top", "Firefox", "Windows",
                "1.2.3", "4.5.6", "req-123", "corr.456"));

        assertEquals("https://developers.example.test/suggestions", safe.pageUrl());
        assertEquals("/suggestions", safe.route());
        assertEquals("req-123", safe.requestId());
        assertThrows(RuntimeException.class, () -> sanitizer.sanitize(
                new FeedbackContextRequest(null, "/dashboard?api_key=plaintext",
                        null, null, null, null, null, null)));
    }

    @Test
    void onlyAllowedStatusTransitionsCanBeApplied() {
        assertTrue(FeedbackStatus.NEW.mayTransitionTo(FeedbackStatus.ACKNOWLEDGED));
        assertTrue(FeedbackStatus.REVIEWING.mayTransitionTo(FeedbackStatus.IN_PROGRESS));
        assertFalse(FeedbackStatus.NEW.mayTransitionTo(FeedbackStatus.CLOSED));
        assertFalse(FeedbackStatus.CLOSED.mayTransitionTo(FeedbackStatus.RESOLVED));

        Feedback feedback = Feedback.create("FB-000001", UUID.randomUUID(), UUID.randomUUID(),
                "Developer", "developer@example.test",
                null, null, FeedbackType.BUG, "Title", "Description", null, null,
                null, null, null, null, null, null, Instant.parse("2026-01-01T00:00:00Z"));
        assertThrows(IllegalStateException.class,
                () -> feedback.transition(FeedbackStatus.CLOSED, Instant.now()));
        feedback.transition(FeedbackStatus.ACKNOWLEDGED, Instant.now());
        feedback.transition(FeedbackStatus.REVIEWING, Instant.now());
        feedback.transition(FeedbackStatus.IN_PROGRESS, Instant.now());
        feedback.transition(FeedbackStatus.RESOLVED, Instant.now());
        feedback.transition(FeedbackStatus.CLOSED, Instant.now());
        assertEquals(FeedbackStatus.CLOSED, feedback.getStatus());
        assertNull(feedback.getResolvedAt());
        feedback.reopen(Instant.now());
        assertEquals(FeedbackStatus.ACKNOWLEDGED, feedback.getStatus());
    }

    @Test
    void developerLookupIsScopedByBothOrganizationAndUser() {
        FeedbackRepository feedbackRepository = mock(FeedbackRepository.class);
        FeedbackCommentRepository commentRepository = mock(FeedbackCommentRepository.class);
        FeedbackEventRepository eventRepository = mock(FeedbackEventRepository.class);
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        OrganizationRepository organizationRepository = mock(OrganizationRepository.class);
        ProjectEnvironmentRepository environmentRepository = mock(ProjectEnvironmentRepository.class);
        ProjectAccessScope projectAccessScope = mock(ProjectAccessScope.class);
        FeedbackRateLimiter rateLimiter = mock(FeedbackRateLimiter.class);
        EntityManager entityManager = mock(EntityManager.class);
        OutboxService outbox = mock(OutboxService.class);
        NotificationService notificationService = mock(NotificationService.class);

        FeedbackService service = new FeedbackService(feedbackRepository, commentRepository,
                eventRepository, projectRepository, organizationRepository, environmentRepository, projectAccessScope,
                new FeedbackContextSanitizer(), rateLimiter, entityManager,
                java.time.Clock.systemUTC(), outbox, notificationService);
        UUID userId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        AuthenticatedUser principal = new AuthenticatedUser(userId, organizationId,
                UUID.randomUUID(), "developer@example.test", "Developer", java.util.Set.of());
        when(feedbackRepository.findByPublicReferenceAndOrganizationIdAndUserId(
                "FB-000012", organizationId, userId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.get(principal, "FB-000012"));
        verify(feedbackRepository).findByPublicReferenceAndOrganizationIdAndUserId(
                "FB-000012", organizationId, userId);
    }

    @Test
    void developerItemDtoDoesNotExposeDatabaseFeedbackId() {
        assertTrue(java.util.Arrays.stream(FeedbackItemView.class.getRecordComponents())
                .noneMatch(component -> component.getName().equals("id")
                        || component.getName().equals("feedbackId")));
    }

    @Test
    void developerCommentReadsSelectOnlyPublicVisibility() {
        FeedbackRepository feedbackRepository = mock(FeedbackRepository.class);
        FeedbackCommentRepository commentRepository = mock(FeedbackCommentRepository.class);
        UUID organizationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Feedback feedback = Feedback.create("FB-000002", organizationId, userId,
                "Developer", "developer@example.test", null, null, FeedbackType.BUG,
                "Title", "Description", null, null, null, null, null, null, null, null, Instant.now());
        when(feedbackRepository.findByPublicReferenceAndOrganizationIdAndUserId(
                feedback.getPublicReference(), organizationId, userId)).thenReturn(Optional.of(feedback));
        when(commentRepository.findByFeedbackIdAndVisibilityOrderByCreatedAtAsc(
                feedback.getId(), FeedbackVisibility.PUBLIC)).thenReturn(java.util.List.of());

        FeedbackService service = new FeedbackService(feedbackRepository, commentRepository,
                mock(FeedbackEventRepository.class), mock(ProjectRepository.class),
                mock(OrganizationRepository.class), mock(ProjectEnvironmentRepository.class),
                mock(ProjectAccessScope.class), new FeedbackContextSanitizer(),
                mock(FeedbackRateLimiter.class), mock(EntityManager.class),
                java.time.Clock.systemUTC(), mock(OutboxService.class), mock(NotificationService.class));
        AuthenticatedUser principal = new AuthenticatedUser(userId, organizationId,
                UUID.randomUUID(), "developer@example.test", "Developer", java.util.Set.of());

        assertTrue(service.comments(principal, feedback.getPublicReference()).items().isEmpty());
        verify(commentRepository).findByFeedbackIdAndVisibilityOrderByCreatedAtAsc(
                feedback.getId(), FeedbackVisibility.PUBLIC);
        verify(commentRepository, never()).findByFeedbackIdOrderByCreatedAtAsc(feedback.getId());
    }

    @Test
    void operatorReadsRequireAnExplicitSupportCapability() {
        FeedbackRepository feedbackRepository = mock(FeedbackRepository.class);
        FeedbackService service = new FeedbackService(feedbackRepository,
                mock(FeedbackCommentRepository.class), mock(FeedbackEventRepository.class),
                mock(ProjectRepository.class), mock(OrganizationRepository.class),
                mock(ProjectEnvironmentRepository.class), mock(ProjectAccessScope.class),
                new FeedbackContextSanitizer(), mock(FeedbackRateLimiter.class),
                mock(EntityManager.class), java.time.Clock.systemUTC(),
                mock(OutboxService.class), mock(NotificationService.class));
        AuthenticatedOperator operator = new AuthenticatedOperator(UUID.randomUUID(),
                "operator:test", java.util.Set.of(), "reviewing");

        assertThrows(AuthenticatedOperator.OperatorAuthorizationException.class,
                () -> service.getForOperator(operator, "FB-000003"));
        verify(feedbackRepository, never()).findByPublicReference("FB-000003");
    }
}
