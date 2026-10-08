package com.pesaguard.backend.feedback.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Locale;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.pesaguard.backend.common.api.RequestContext;
import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.common.exception.ResourceConflictException;
import com.pesaguard.backend.common.exception.ResourceNotFoundException;
import com.pesaguard.backend.environment.infrastructure.ProjectEnvironmentRepository;
import com.pesaguard.backend.feedback.api.AdminFeedbackCommentView;
import com.pesaguard.backend.feedback.api.AdminFeedbackCommentListView;
import com.pesaguard.backend.feedback.api.AdminFeedbackDetailView;
import com.pesaguard.backend.feedback.api.AdminFeedbackItemView;
import com.pesaguard.backend.feedback.api.AdminFeedbackPageView;
import com.pesaguard.backend.feedback.api.CreateFeedbackRequest;
import com.pesaguard.backend.feedback.api.FeedbackActivityView;
import com.pesaguard.backend.feedback.api.FeedbackCommentListView;
import com.pesaguard.backend.feedback.api.FeedbackCommentView;
import com.pesaguard.backend.feedback.api.FeedbackContextRequest;
import com.pesaguard.backend.feedback.api.FeedbackItemView;
import com.pesaguard.backend.feedback.api.FeedbackPageView;
import com.pesaguard.backend.feedback.api.UpdateFeedbackRequest;
import com.pesaguard.backend.feedback.domain.Feedback;
import com.pesaguard.backend.feedback.domain.FeedbackAssignmentTeam;
import com.pesaguard.backend.feedback.domain.FeedbackComment;
import com.pesaguard.backend.feedback.domain.FeedbackEvent;
import com.pesaguard.backend.feedback.domain.FeedbackPriority;
import com.pesaguard.backend.feedback.domain.FeedbackStatus;
import com.pesaguard.backend.feedback.domain.FeedbackType;
import com.pesaguard.backend.feedback.domain.FeedbackVisibility;
import com.pesaguard.backend.feedback.infrastructure.FeedbackCommentRepository;
import com.pesaguard.backend.feedback.infrastructure.FeedbackEventRepository;
import com.pesaguard.backend.feedback.infrastructure.FeedbackRepository;
import com.pesaguard.backend.notifications.application.NotificationService;
import com.pesaguard.backend.notifications.domain.NotificationType;
import com.pesaguard.backend.organization.infrastructure.OrganizationRepository;
import com.pesaguard.backend.outbox.application.OutboxService;
import com.pesaguard.backend.platformadmin.domain.AuthenticatedOperator;
import com.pesaguard.backend.platformadmin.domain.OperatorCapability;
import com.pesaguard.backend.project.application.ProjectAccessScope;
import com.pesaguard.backend.project.infrastructure.ProjectRepository;
import com.pesaguard.backend.security.principals.AuthenticatedUser;
import com.pesaguard.backend.support.application.SensitiveDataRedactor;

import jakarta.persistence.EntityManager;

@Service
public class FeedbackService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_QUERY_LENGTH = 100;

    private final FeedbackRepository feedbackRepository;
    private final FeedbackCommentRepository commentRepository;
    private final FeedbackEventRepository eventRepository;
    private final ProjectRepository projectRepository;
    private final OrganizationRepository organizationRepository;
    private final ProjectEnvironmentRepository environmentRepository;
    private final ProjectAccessScope projectAccessScope;
    private final FeedbackContextSanitizer contextSanitizer;
    private final FeedbackRateLimiter rateLimiter;
    private final EntityManager entityManager;
    private final Clock clock;
    private final OutboxService outbox;
    private final NotificationService notificationService;

    public FeedbackService(FeedbackRepository feedbackRepository,
            FeedbackCommentRepository commentRepository,
            FeedbackEventRepository eventRepository,
            ProjectRepository projectRepository,
            OrganizationRepository organizationRepository,
            ProjectEnvironmentRepository environmentRepository,
            ProjectAccessScope projectAccessScope,
            FeedbackContextSanitizer contextSanitizer,
            FeedbackRateLimiter rateLimiter,
            EntityManager entityManager,
            Clock clock,
            OutboxService outbox,
            NotificationService notificationService) {
        this.feedbackRepository = feedbackRepository;
        this.commentRepository = commentRepository;
        this.eventRepository = eventRepository;
        this.projectRepository = projectRepository;
        this.organizationRepository = organizationRepository;
        this.environmentRepository = environmentRepository;
        this.projectAccessScope = projectAccessScope;
        this.contextSanitizer = contextSanitizer;
        this.rateLimiter = rateLimiter;
        this.entityManager = entityManager;
        this.clock = clock;
        this.outbox = outbox;
        this.notificationService = notificationService;
    }

    @Transactional
    public FeedbackItemView create(AuthenticatedUser principal, CreateFeedbackRequest request,
            String idempotencyKey) {
        requireDeveloper(principal);
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._~-]{1,128}")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED",
                    "A valid Idempotency-Key header is required.");
        }
        FeedbackContextSanitizer.SanitizedContext context = contextSanitizer.sanitize(request.context());
        ProjectAccessScope.Scope scope = projectAccessScope.resolve(
                principal, request.projectId(), request.environmentId());
        UUID projectId = scope.projectId();
        UUID environmentId = scope.environmentId();
        String title = SensitiveDataRedactor.redact(request.title().trim());
        String description = SensitiveDataRedactor.redact(request.description().trim());
        String requestHash = hashRequest(request.type(), title, description,
                projectId, environmentId, context);

        int inserted = entityManager.createNativeQuery("""
                insert into feedback_create_idempotency
                    (id, organization_id, user_id, idempotency_key, request_hash, created_at)
                values (:id, :organizationId, :userId, :key, :hash, :createdAt)
                on conflict (organization_id, user_id, idempotency_key) do nothing
                """)
                .setParameter("id", UUID.randomUUID())
                .setParameter("organizationId", principal.organizationId())
                .setParameter("userId", principal.userId())
                .setParameter("key", idempotencyKey)
                .setParameter("hash", requestHash)
                .setParameter("createdAt", clock.instant())
                .executeUpdate();
        Object[] idempotency = (Object[]) entityManager.createNativeQuery("""
                select request_hash, feedback_reference from feedback_create_idempotency
                where organization_id = :organizationId and user_id = :userId
                  and idempotency_key = :key
                """)
                .setParameter("organizationId", principal.organizationId())
                .setParameter("userId", principal.userId())
                .setParameter("key", idempotencyKey)
                .getSingleResult();
        if (!requestHash.equals(String.valueOf(idempotency[0]))) {
            throw new ResourceConflictException("IDEMPOTENCY_KEY_REUSED",
                    "This Idempotency-Key was already used for different feedback.");
        }
        if (inserted == 0) {
            String existingReference = (String) idempotency[1];
            if (existingReference == null) {
                throw new ResourceConflictException("FEEDBACK_REQUEST_IN_PROGRESS",
                        "A request with this Idempotency-Key is still being processed.");
            }
            return item(ownedFeedback(principal, existingReference));
        }
        if (!rateLimiter.allow(principal.userId(), "CREATE", 10)) {
            throw tooManyRequests("Feedback creation limit reached. Try again later.");
        }

        long sequence = ((Number) entityManager.createNativeQuery(
                "select nextval('feedback_public_reference_seq')").getSingleResult()).longValue();
        String reference = "FB-" + String.format(Locale.ROOT, "%06d", sequence);
        InstantHolder now = new InstantHolder(clock.instant());
        Feedback feedback = Feedback.create(reference, principal.organizationId(), principal.userId(),
                displayName(principal.displayName()), principal.email(),
                projectId, environmentId, request.type(), title, description,
                context.pageUrl(), context.route(), context.browser(), context.operatingSystem(),
                context.applicationVersion(), context.frontendVersion(), context.requestId(),
                context.correlationId(), now.value());
        Feedback saved = feedbackRepository.saveAndFlush(feedback);
        entityManager.createNativeQuery("""
                update feedback_create_idempotency set feedback_reference = :reference
                where organization_id = :organizationId and user_id = :userId
                  and idempotency_key = :key
                """)
                .setParameter("reference", reference)
                .setParameter("organizationId", principal.organizationId())
                .setParameter("userId", principal.userId())
                .setParameter("key", idempotencyKey)
                .executeUpdate();
        recordActivity(saved, principal.userId(), "DEVELOPER", "CREATED",
                null, saved.getStatus().name(), developerAuditMetadata());
        recordOutbox(saved, "feedback.created");
        notificationService.enqueue(saved.getOrganizationId(), saved.getUserId(),
                NotificationType.SUPPORT_TICKET_CREATED, "Feedback received",
                "We received your feedback " + saved.getPublicReference()
                        + ". Sign in to the developer platform to view it.",
                "feedback", saved.getPublicReference(), null,
                saved.getPublicReference() + ":received");
        return item(saved);
    }

    @Transactional(readOnly = true)
    public FeedbackPageView list(AuthenticatedUser principal, int page, int pageSize,
            FeedbackType type, FeedbackStatus status, String query) {
        requireDeveloper(principal);
        validatePage(page, pageSize);
        String search = normalizeQuery(query);
        Page<Feedback> result = feedbackRepository.findDeveloperFeedback(
                principal.organizationId(), principal.userId(), type, status, search,
                PageRequest.of(page, pageSize, Sort.by(Sort.Direction.DESC, "updatedAt")));
        return new FeedbackPageView(result.getContent().stream().map(this::item).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public FeedbackItemView get(AuthenticatedUser principal, String reference) {
        requireDeveloper(principal);
        return item(ownedFeedback(principal, reference));
    }

    @Transactional(readOnly = true)
    public FeedbackCommentListView comments(AuthenticatedUser principal, String reference) {
        requireDeveloper(principal);
        Feedback feedback = ownedFeedback(principal, reference);
        return new FeedbackCommentListView(commentRepository
                .findByFeedbackIdAndVisibilityOrderByCreatedAtAsc(feedback.getId(), FeedbackVisibility.PUBLIC)
                .stream().map(FeedbackCommentView::from).toList());
    }

    @Transactional
    public FeedbackCommentView addDeveloperComment(AuthenticatedUser principal, String reference, String body) {
        requireDeveloper(principal);
        Feedback feedback = ownedFeedbackForUpdate(principal, reference);
        if (!rateLimiter.allow(principal.userId(), "COMMENT", 30)) {
            throw tooManyRequests("Feedback comment limit reached. Try again later.");
        }
        String safeBody = safeComment(body);
        FeedbackComment comment = commentRepository.saveAndFlush(FeedbackComment.create(feedback.getId(),
                principal.userId(), displayName(principal.displayName()), "DEVELOPER", safeBody,
                FeedbackVisibility.PUBLIC, clock.instant()));
        recordActivity(feedback, principal.userId(), "DEVELOPER", "COMMENT_ADDED",
                null, null, Map.of("visibility", "PUBLIC"));
        recordOutbox(feedback, "feedback.comment_added");
        return FeedbackCommentView.from(comment);
    }

    @Transactional
    public FeedbackItemView reopen(AuthenticatedUser principal, String reference) {
        requireDeveloper(principal);
        Feedback feedback = ownedFeedbackForUpdate(principal, reference);
        try {
            FeedbackStatus old = feedback.getStatus();
            feedback.reopen(clock.instant());
            feedbackRepository.saveAndFlush(feedback);
            recordActivity(feedback, principal.userId(), "DEVELOPER", "REOPENED",
                    old.name(), feedback.getStatus().name(), developerAuditMetadata());
            recordOutbox(feedback, "feedback.status_changed");
            return item(feedback);
        } catch (IllegalStateException invalidTransition) {
            throw new ResourceConflictException("FEEDBACK_NOT_REOPENABLE",
                    "Only resolved or closed feedback can be reopened.");
        }
    }

    @Transactional(readOnly = true)
    public AdminFeedbackPageView listForOperator(AuthenticatedOperator operator, int page, int pageSize,
            UUID organizationId, UUID userId, UUID projectId, FeedbackType type,
            FeedbackStatus status, FeedbackPriority priority, FeedbackAssignmentTeam team, String query) {
        operator.require(OperatorCapability.SUPPORT_READ);
        validatePage(page, pageSize);
        Page<Feedback> result = feedbackRepository.findOperatorFeedback(
                organizationId, userId, projectId, type, status, priority,
                team == null ? null : team.name(), normalizeQuery(query),
                PageRequest.of(page, pageSize, Sort.by(Sort.Direction.DESC, "updatedAt")));
        return new AdminFeedbackPageView(result.getContent().stream()
                .map(this::adminItem).toList(), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public AdminFeedbackDetailView getForOperator(AuthenticatedOperator operator, String reference) {
        operator.require(OperatorCapability.SUPPORT_READ);
        Feedback feedback = feedbackRepository.findByPublicReference(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Feedback"));
        return adminDetail(feedback);
    }

    @Transactional(readOnly = true)
    public AdminFeedbackCommentListView commentsForOperator(AuthenticatedOperator operator, String reference) {
        operator.require(OperatorCapability.SUPPORT_READ);
        Feedback feedback = feedbackRepository.findByPublicReference(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Feedback"));
        return new AdminFeedbackCommentListView(commentRepository
                .findByFeedbackIdOrderByCreatedAtAsc(feedback.getId())
                .stream().map(AdminFeedbackCommentView::from)
                .toList());
    }

    @Transactional
    public AdminFeedbackDetailView updateForOperator(AuthenticatedOperator operator,
            String reference, UpdateFeedbackRequest request) {
        requireFeedbackOperatorMutation(operator);
        if (request == null || (request.status() == null && request.priority() == null)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "EMPTY_FEEDBACK_UPDATE",
                    "At least one feedback field must be provided.");
        }
        Feedback feedback = operatorFeedbackForUpdate(reference);
        String reason = operator.requireReason();
        if (request.priority() != null && request.priority() != feedback.getPriority()) {
            FeedbackPriority old = feedback.getPriority();
            feedback.setPriority(request.priority(), clock.instant());
            recordActivity(feedback, operator.operatorId(), "OPERATOR", "PRIORITY_CHANGED",
                    old.name(), request.priority().name(), operatorAuditMetadata(reason));
            recordOutbox(feedback, "feedback.priority_changed");
        }
        if (request.status() != null && request.status() != feedback.getStatus()) {
            transitionForOperator(feedback, operator, request.status(), reason);
        }
        feedbackRepository.saveAndFlush(feedback);
        return adminDetail(feedback);
    }

    @Transactional
    public AdminFeedbackDetailView assignForOperator(AuthenticatedOperator operator,
            String reference, FeedbackAssignmentTeam team) {
        requireFeedbackOperatorMutation(operator);
        Feedback feedback = operatorFeedbackForUpdate(reference);
        String reason = operator.requireReason();
        String old = feedback.getAssignedTeam();
        if (team != null && team.name().equals(old)) {
            return adminDetail(feedback);
        }
        feedback.assign(team, null, clock.instant());
        feedbackRepository.saveAndFlush(feedback);
        recordActivity(feedback, operator.operatorId(), "OPERATOR", "ASSIGNED",
                old, team.name(), operatorAuditMetadata(reason));
        recordOutbox(feedback, "feedback.assigned", Map.of("assignedTeam", team.name()));
        return adminDetail(feedback);
    }

    @Transactional
    public FeedbackCommentView addOperatorComment(AuthenticatedOperator operator,
            String reference, String body) {
        requireFeedbackOperatorMutation(operator);
        Feedback feedback = operatorFeedbackForUpdate(reference);
        String reason = operator.requireReason();
        FeedbackComment comment = commentRepository.saveAndFlush(FeedbackComment.create(feedback.getId(),
                operator.operatorId(), "PesaGuard team", "OPERATOR", safeComment(body),
                FeedbackVisibility.PUBLIC, clock.instant()));
        recordActivity(feedback, operator.operatorId(), "OPERATOR", "COMMENT_ADDED",
                null, null, operatorAuditMetadata(reason, "PUBLIC"));
        recordOutbox(feedback, "feedback.comment_added");
        notificationService.enqueue(feedback.getOrganizationId(), feedback.getUserId(),
                NotificationType.SUPPORT_TICKET_CREATED, "A reply to your feedback",
                "The PesaGuard team replied to feedback " + feedback.getPublicReference()
                        + ". Sign in to the developer platform to view the reply.",
                "feedback", feedback.getPublicReference(), null,
                feedback.getPublicReference() + ":reply:" + comment.getId());
        return FeedbackCommentView.from(comment);
    }

    @Transactional
    public AdminFeedbackDetailView addInternalNote(AuthenticatedOperator operator,
            String reference, String body) {
        requireFeedbackOperatorMutation(operator);
        Feedback feedback = operatorFeedbackForUpdate(reference);
        String reason = operator.requireReason();
        FeedbackComment note = commentRepository.saveAndFlush(FeedbackComment.create(feedback.getId(),
                operator.operatorId(), "PesaGuard team", "OPERATOR", safeComment(body),
                FeedbackVisibility.INTERNAL, clock.instant()));
        recordActivity(feedback, operator.operatorId(), "OPERATOR", "INTERNAL_NOTE_ADDED",
                null, null, operatorAuditMetadata(reason, "INTERNAL"));
        // Internal note content is intentionally absent from outbox payloads,
        // notification bodies, public DTOs, and event metadata.
        return adminDetail(feedback);
    }

    private void transitionForOperator(Feedback feedback, AuthenticatedOperator operator,
            FeedbackStatus next, String reason) {
        FeedbackStatus old = feedback.getStatus();
        try {
            feedback.transition(next, clock.instant());
        } catch (IllegalStateException invalidTransition) {
            throw new ResourceConflictException("INVALID_FEEDBACK_TRANSITION",
                    "The requested feedback status transition is not allowed.");
        }
        recordActivity(feedback, operator.operatorId(), "OPERATOR",
                next == FeedbackStatus.RESOLVED ? "RESOLVED"
                        : next == FeedbackStatus.CLOSED ? "CLOSED" : "STATUS_CHANGED",
                old.name(), next.name(), operatorAuditMetadata(reason));
        String event = next == FeedbackStatus.RESOLVED ? "feedback.resolved"
                : next == FeedbackStatus.CLOSED ? "feedback.closed" : "feedback.status_changed";
        recordOutbox(feedback, event);
        NotificationType notificationType = next == FeedbackStatus.RESOLVED || next == FeedbackStatus.CLOSED
                ? NotificationType.SUPPORT_TICKET_RESOLVED : NotificationType.SUPPORT_TICKET_CREATED;
        notificationService.enqueue(feedback.getOrganizationId(), feedback.getUserId(),
                notificationType, "Feedback status updated",
                "Feedback " + feedback.getPublicReference() + " is now " + next.name() + ".",
                "feedback", feedback.getPublicReference(), null,
                feedback.getPublicReference() + ":status:" + next.name());
    }

    private Feedback ownedFeedback(AuthenticatedUser principal, String reference) {
        return feedbackRepository.findByPublicReferenceAndOrganizationIdAndUserId(
                        reference, principal.organizationId(), principal.userId())
                .orElseThrow(() -> new ResourceNotFoundException("Feedback"));
    }

    private Feedback ownedFeedbackForUpdate(AuthenticatedUser principal, String reference) {
        Feedback feedback = feedbackRepository.findForUpdate(reference)
                .filter(item -> item.getOrganizationId().equals(principal.organizationId())
                        && item.getUserId().equals(principal.userId()))
                .orElseThrow(() -> new ResourceNotFoundException("Feedback"));
        return feedback;
    }

    private Feedback operatorFeedbackForUpdate(String reference) {
        return feedbackRepository.findForUpdate(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Feedback"));
    }

    private void requireDeveloper(AuthenticatedUser principal) {
        if (principal == null || principal.serviceAccount()) {
            throw new AccessDeniedException("Developer feedback requires an interactive developer session.");
        }
    }

    private static void requireFeedbackOperatorMutation(AuthenticatedOperator operator) {
        operator.require(OperatorCapability.SUPPORT_RESOLVE);
        operator.requireReason();
    }

    private AdminFeedbackDetailView adminDetail(Feedback feedback) {
        AdminFeedbackItemView item = adminItem(feedback);
        List<AdminFeedbackCommentView> comments = commentRepository
                .findByFeedbackIdOrderByCreatedAtAsc(feedback.getId())
                .stream().map(AdminFeedbackCommentView::from).toList();
        List<FeedbackActivityView> activity = eventRepository
                .findByFeedbackIdOrderByCreatedAtAsc(feedback.getId())
                .stream().map(FeedbackActivityView::from).toList();
        return new AdminFeedbackDetailView(item, feedback.getPageUrl(), feedback.getRoute(),
                feedback.getBrowser(), feedback.getOperatingSystem(), feedback.getApplicationVersion(),
                feedback.getFrontendVersion(), feedback.getRequestId(), feedback.getCorrelationId(),
                comments, activity);
    }

    private AdminFeedbackItemView adminItem(Feedback feedback) {
        return new AdminFeedbackItemView(item(feedback),
                organizationRepository.findById(feedback.getOrganizationId())
                        .map(organization -> organization.getName()).orElse("Unknown organization"),
                feedback.getRequesterDisplayName(), feedback.getRequesterEmail(), feedback.getAssignedTeam());
    }

    private FeedbackItemView item(Feedback feedback) {
        String projectName = feedback.getProjectId() == null ? null
                : projectRepository.findByIdAndOrganizationId(
                        feedback.getProjectId(), feedback.getOrganizationId())
                        .map(project -> project.getName()).orElse(null);
        String environmentName = feedback.getEnvironmentId() == null ? null
                : environmentRepository.findByIdAndOrganizationId(
                        feedback.getEnvironmentId(), feedback.getOrganizationId())
                        .map(environment -> environment.getName()).orElse(null);
        long count = commentRepository.countByFeedbackIdAndVisibility(
                feedback.getId(), FeedbackVisibility.PUBLIC);
        return FeedbackItemView.from(feedback, projectName, environmentName, count);
    }

    private void recordActivity(Feedback feedback, UUID actorId, String actorType,
            String eventType, String oldValue, String newValue, Map<String, String> metadata) {
        eventRepository.save(FeedbackEvent.record(feedback.getId(), actorId, actorType,
                eventType, oldValue, newValue, metadata, clock.instant()));
    }

    private Map<String, String> developerAuditMetadata() {
        return Map.of("requestId", RequestContext.currentRequestId().toString());
    }

    private Map<String, String> operatorAuditMetadata(String reason) {
        return operatorAuditMetadata(reason, "ADMIN");
    }

    private Map<String, String> operatorAuditMetadata(String reason, String visibility) {
        return Map.of("reason", reason,
                "requestId", RequestContext.currentRequestId().toString(),
                "ipAddress", valueOrUnknown(RequestContext.currentRemoteAddress()),
                "visibility", visibility);
    }

    private void recordOutbox(Feedback feedback, String eventType) {
        recordOutbox(feedback, eventType, Map.of());
    }

    private void recordOutbox(Feedback feedback, String eventType, Map<String, String> attributes) {
        String payload = "{\"reference\":\"" + escapeJson(feedback.getPublicReference())
                + "\",\"type\":\"" + feedback.getType().name()
                + "\",\"status\":\"" + feedback.getStatus().name()
                + "\",\"priority\":\"" + feedback.getPriority().name() + "\""
                + attributes.entrySet().stream().map(entry -> ",\"" + escapeJson(entry.getKey())
                        + "\":\"" + escapeJson(entry.getValue()) + "\"").collect(java.util.stream.Collectors.joining())
                + "}";
        outbox.recordTenantEvent(eventType, 1, payload, feedback.getOrganizationId(),
                feedback.getProjectId(), feedback.getEnvironmentId(),
                feedback.getCorrelationId(), RequestContext.currentRequestId().toString());
    }

    private String safeComment(String body) {
        if (!StringUtils.hasText(body) || body.trim().length() > 4000) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_FEEDBACK_COMMENT",
                    "Feedback comments must contain 1 to 4,000 characters.");
        }
        return SensitiveDataRedactor.redact(body.trim());
    }

    private String hashRequest(FeedbackType type, String title, String description,
            UUID projectId, UUID environmentId, FeedbackContextSanitizer.SanitizedContext context) {
        StringBuilder canonical = new StringBuilder();
        for (String value : new String[] {
                type.name(), title, description, projectId == null ? null : projectId.toString(),
                environmentId == null ? null : environmentId.toString(), context.pageUrl(),
                context.route(), context.browser(), context.operatingSystem(),
                context.applicationVersion(), context.frontendVersion(),
                context.requestId(), context.correlationId() }) {
            if (value == null) {
                canonical.append("-1:");
            } else {
                canonical.append(value.length()).append(':').append(value);
            }
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        }
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }

    private static void validatePage(int page, int pageSize) {
        if (page < 0 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_FEEDBACK_PAGE",
                    "Page must be non-negative and pageSize must be between 1 and 100.");
        }
    }

    private static String normalizeQuery(String query) {
        if (!StringUtils.hasText(query)) return null;
        String trimmed = query.trim();
        if (trimmed.length() > MAX_QUERY_LENGTH) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_FEEDBACK_QUERY",
                    "Feedback search queries must be at most 100 characters.");
        }
        return trimmed;
    }

    private static String displayName(String value) {
        if (!StringUtils.hasText(value)) return "Developer";
        String cleaned = value.trim();
        return cleaned.length() > 120 ? cleaned.substring(0, 120) : cleaned;
    }

    private static String valueOrUnknown(String value) {
        return value == null ? "unknown" : value;
    }

    private static BusinessException tooManyRequests(String message) {
        return new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "FEEDBACK_RATE_LIMITED", message);
    }

    private record InstantHolder(java.time.Instant value) {
    }
}
