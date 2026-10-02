package com.pesaguard.backend.securitycenter.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.audit.application.AuditService;
import com.pesaguard.backend.common.api.RequestContext;
import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.common.exception.ResourceNotFoundException;
import com.pesaguard.backend.security.principals.AuthenticatedUser;
import com.pesaguard.backend.securitycenter.domain.EmergencyResponse;
import com.pesaguard.backend.securitycenter.domain.SecurityEvent;
import com.pesaguard.backend.securitycenter.domain.SecurityEventType;
import com.pesaguard.backend.securitycenter.infrastructure.SecurityEventEntity;
import com.pesaguard.backend.securitycenter.infrastructure.SecurityEventRepository;

/**
 * Records and resolves security signals.
 *
 * <p>Two rules are enforced here rather than left to convention:
 *
 * <ul>
 *   <li><b>Detection never resolves.</b> Only a named human can move an event out
 *       of {@code OPEN}, and doing so always records who and why. A detector that
 *       closes its own findings makes its silence indistinguishable from
 *       "nothing is wrong".</li>
 *   <li><b>Every read and write is tenant-scoped.</b> The organization comes from
 *       the authenticated principal, never from a request body, so one tenant
 *       cannot read or resolve another's signals.</li>
 * </ul>
 */
@Service
public class SecurityEventService {

    private final SecurityEventRepository repository;
    private final AuditService auditService;
    private final Clock clock;

    public SecurityEventService(SecurityEventRepository repository, AuditService auditService,
            Clock clock) {
        this.repository = repository;
        this.auditService = auditService;
        this.clock = clock;
    }

    /**
     * Records an observed signal.
     *
     * <p>Always {@code OPEN}. There is deliberately no overload that accepts a
     * resolution: making it impossible to create a pre-resolved event is what
     * guarantees every finding in the list was once genuinely open.
     */
    @Transactional
    public SecurityEvent.Record record(UUID organizationId, SecurityEventType type,
            UUID subjectId, String subjectKind, String detail) {
        SecurityEvent.Record event = SecurityEvent.Record.detected(organizationId, type,
                subjectId, subjectKind, detail, clock.instant());
        return toRecord(repository.save(new SecurityEventEntity(event)));
    }

    /**
     * Resolves a signal.
     *
     * <p>Re-resolving is refused rather than silently overwritten. The first
     * decision is part of the record, and a second attempt usually means two
     * people are looking at the same finding and have not spoken to each other.
     */
    @Transactional
    public SecurityEvent.Record resolve(AuthenticatedUser principal, UUID eventId,
            SecurityEvent.Resolution resolution, String note) {
        if (resolution == null || resolution == SecurityEvent.Resolution.OPEN) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_RESOLUTION",
                    "A resolution other than OPEN is required.");
        }
        if (principal == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                    "Authentication is required.");
        }
        String normalised = EmergencyResponse.normaliseReason(note);

        SecurityEvent.Record event = repository.findByIdAndOrganizationId(eventId,
                principal.organizationId())
                .map(SecurityEventService::toRecord)
                .orElseThrow(() -> new ResourceNotFoundException("Security event"));

        if (event.isClosed()) {
            throw new BusinessException(HttpStatus.CONFLICT, "ALREADY_RESOLVED",
                    "This security event has already been resolved.");
        }

        SecurityEvent.Record resolved = new SecurityEvent.Record(event.id(),
                event.organizationId(), event.type(), event.subjectId(), event.subjectKind(),
                event.detail(), event.detectedAt(), resolution, principal.userId(),
                clock.instant(), normalised);

        SecurityEvent.Record saved = toRecord(repository.save(
                new SecurityEventEntity(resolved)));
        auditService.append(principal.organizationId(), principal.userId(),
                "security_event.resolved", "security_event", eventId.toString(),
                RequestContext.currentRequestId(),
                java.util.Map.of("resolution", resolution.name(), "type", event.type().name()));
        return saved;
    }

    /** Open signals for a tenant, most recent first. */
    @Transactional(readOnly = true)
    public java.util.List<SecurityEvent.Record> openFor(AuthenticatedUser principal) {
        return repository.findByOrganizationIdAndResolutionOrderByDetectedAtDesc(
                principal.organizationId(), SecurityEvent.Resolution.OPEN).stream()
                .map(SecurityEventService::toRecord)
                .toList();
    }

    /** A tenant's full signal history. */
    @Transactional(readOnly = true)
    public java.util.List<SecurityEvent.Record> historyFor(AuthenticatedUser principal) {
        return repository.findByOrganizationIdOrderByDetectedAtDesc(
                principal.organizationId()).stream()
                .map(SecurityEventService::toRecord)
                .toList();
    }
    private static SecurityEvent.Record toRecord(
            com.pesaguard.backend.securitycenter.infrastructure.SecurityEventEntity entity) {
        return new SecurityEvent.Record(entity.getId(), entity.getOrganizationId(),
                entity.getType(), entity.getSubjectId(), entity.getSubjectKind(),
                entity.getDetail(), entity.getDetectedAt(), entity.getResolution(),
                entity.getResolvedBy(), entity.getResolvedAt(),
                entity.getResolutionNote());
    }
}
