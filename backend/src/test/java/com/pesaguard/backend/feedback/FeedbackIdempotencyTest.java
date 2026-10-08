package com.pesaguard.backend.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.pesaguard.backend.common.exception.ResourceConflictException;
import com.pesaguard.backend.environment.infrastructure.ProjectEnvironmentRepository;
import com.pesaguard.backend.feedback.api.CreateFeedbackRequest;
import com.pesaguard.backend.feedback.application.FeedbackContextSanitizer;
import com.pesaguard.backend.feedback.application.FeedbackRateLimiter;
import com.pesaguard.backend.feedback.application.FeedbackService;
import com.pesaguard.backend.feedback.domain.Feedback;
import com.pesaguard.backend.feedback.domain.FeedbackType;
import com.pesaguard.backend.feedback.infrastructure.FeedbackCommentRepository;
import com.pesaguard.backend.feedback.infrastructure.FeedbackEventRepository;
import com.pesaguard.backend.feedback.infrastructure.FeedbackRepository;
import com.pesaguard.backend.notifications.application.NotificationService;
import com.pesaguard.backend.organization.infrastructure.OrganizationRepository;
import com.pesaguard.backend.outbox.application.OutboxService;
import com.pesaguard.backend.project.application.ProjectAccessScope;
import com.pesaguard.backend.project.infrastructure.ProjectRepository;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

class FeedbackIdempotencyTest {

    @Test
    void retryReturnsOriginalRecordAndChangedPayloadIsRejected() {
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

        Query reserveQuery = mock(Query.class);
        Query lookupQuery = mock(Query.class);
        Query sequenceQuery = mock(Query.class);
        Query storeReferenceQuery = mock(Query.class);
        AtomicInteger insertCount = new AtomicInteger();
        AtomicReference<String> attemptedHash = new AtomicReference<>();
        AtomicReference<String> storedHash = new AtomicReference<>();
        AtomicReference<String> storedReference = new AtomicReference<>();
        AtomicReference<Feedback> storedFeedback = new AtomicReference<>();

        when(entityManager.createNativeQuery(startsWith("insert into feedback_create_idempotency")))
                .thenReturn(reserveQuery);
        when(reserveQuery.setParameter(anyString(), any())).thenAnswer(invocation -> {
            if ("hash".equals(invocation.getArgument(0))) {
                attemptedHash.set(invocation.getArgument(1).toString());
            }
            return reserveQuery;
        });
        when(reserveQuery.executeUpdate()).thenAnswer(invocation -> {
            if (insertCount.getAndIncrement() == 0) {
                storedHash.set(attemptedHash.get());
                return 1;
            }
            return 0;
        });
        when(entityManager.createNativeQuery(startsWith("select request_hash, feedback_reference")))
                .thenReturn(lookupQuery);
        when(lookupQuery.setParameter(anyString(), any())).thenReturn(lookupQuery);
        when(lookupQuery.getSingleResult()).thenAnswer(invocation ->
                new Object[] { storedHash.get(), storedReference.get() });
        when(entityManager.createNativeQuery(eq("select nextval('feedback_public_reference_seq')")))
                .thenReturn(sequenceQuery);
        when(sequenceQuery.getSingleResult()).thenReturn(1L);
        when(entityManager.createNativeQuery(startsWith("update feedback_create_idempotency")))
                .thenReturn(storeReferenceQuery);
        when(storeReferenceQuery.setParameter(anyString(), any())).thenAnswer(invocation -> {
            if ("reference".equals(invocation.getArgument(0))) {
                storedReference.set(invocation.getArgument(1).toString());
            }
            return storeReferenceQuery;
        });
        when(storeReferenceQuery.executeUpdate()).thenReturn(1);

        when(projectAccessScope.resolve(any(), eq(null), eq(null)))
                .thenReturn(new ProjectAccessScope.Scope(null, null, null));
        when(rateLimiter.allow(any(), eq("CREATE"), eq(10))).thenReturn(true);
        when(feedbackRepository.saveAndFlush(any(Feedback.class))).thenAnswer(invocation -> {
            Feedback feedback = invocation.getArgument(0);
            storedFeedback.set(feedback);
            return feedback;
        });
        when(feedbackRepository.findByPublicReferenceAndOrganizationIdAndUserId(
                anyString(), any(), any())).thenAnswer(invocation -> Optional.of(storedFeedback.get()));
        when(eventRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(commentRepository.countByFeedbackIdAndVisibility(any(), any())).thenReturn(0L);

        FeedbackService service = new FeedbackService(feedbackRepository, commentRepository,
                eventRepository, projectRepository, organizationRepository, environmentRepository,
                projectAccessScope, new FeedbackContextSanitizer(), rateLimiter, entityManager,
                java.time.Clock.systemUTC(), outbox, notificationService);
        AuthenticatedUser principal = new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "developer@example.test", "Developer", Set.of());
        CreateFeedbackRequest original = new CreateFeedbackRequest(
                FeedbackType.SUGGESTION, "A title", "Some useful feedback", null, null, null);

        var created = service.create(principal, original, "retry-17");
        var retried = service.create(principal, original, "retry-17");
        assertEquals(created.reference(), retried.reference());
        assertEquals("FB-000001", retried.reference());
        verify(feedbackRepository, times(1)).saveAndFlush(any(Feedback.class));
        verify(rateLimiter, times(1)).allow(principal.userId(), "CREATE", 10);

        CreateFeedbackRequest changed = new CreateFeedbackRequest(
                FeedbackType.SUGGESTION, "A different title", "Some useful feedback", null, null, null);
        assertThrows(ResourceConflictException.class,
                () -> service.create(principal, changed, "retry-17"));
        verify(feedbackRepository, times(1)).saveAndFlush(any(Feedback.class));
    }
}
