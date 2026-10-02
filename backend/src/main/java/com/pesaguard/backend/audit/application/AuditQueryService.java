package com.pesaguard.backend.audit.application;

import java.util.List;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.audit.domain.AuditEvent;
import com.pesaguard.backend.audit.infrastructure.AuditEventRepository;
import com.pesaguard.backend.security.credentials.CredentialCryptoService;
import com.pesaguard.backend.rbac.application.AuthorizationService;
import com.pesaguard.backend.rbac.domain.Permission;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

@Service
public class AuditQueryService {

    private final AuditEventRepository repository;
    private final CredentialCryptoService crypto;
    private final AuthorizationService authorizationService;
    private final SecretKey auditKey;

    public AuditQueryService(AuditEventRepository repository, CredentialCryptoService crypto,
            AuthorizationService authorizationService,
            @Qualifier("auditHmacKey") SecretKey auditKey) {
        this.repository = repository;
        this.crypto = crypto;
        this.authorizationService = authorizationService;
        this.auditKey = auditKey;
    }

    @Transactional(readOnly = true)
    public Page<AuditEvent> list(AuthenticatedUser principal, int page, int size) {
        authorizationService.requirePermission(principal, Permission.AUDIT_READ);
        int safeSize = Math.min(Math.max(size, 1), 100);
        return repository.findByOrganizationIdOrderBySequenceNumberDesc(
                principal.organizationId(), PageRequest.of(Math.max(page, 0), safeSize));
    }

    @Transactional(readOnly = true)
    public boolean verifyChain(AuthenticatedUser principal) {
        authorizationService.requirePermission(principal, Permission.AUDIT_READ);
        List<AuditEvent> events = repository.findByOrganizationIdOrderBySequenceNumberAsc(principal.organizationId());
        String previousHash = "0".repeat(64);
        long expectedSequence = 1;
        for (AuditEvent event : events) {
            if (event.getSequenceNumber() != expectedSequence
                    || !constantTime(previousHash, event.getPreviousHash())) {
                return false;
            }
            String canonical = canonical(event);
            if (!constantTime(event.getEventHash(), CredentialCryptoService.hmacSha256(auditKey, canonical))) {
                return false;
            }
            previousHash = event.getEventHash();
            expectedSequence++;
        }
        return true;
    }

    /**
     * The canonical form an event was hashed under.
     *
     * <p>Explicit per version rather than "anything that is not v1 is current":
     * that shortcut makes every historical row fail verification the moment a new
     * version is added, and a chain that reports itself tampered after an upgrade
     * is indistinguishable from a real tamper.
     */
    private String canonical(AuditEvent event) {
        if (event.getHashVersion() == 1) {
            return String.join("\n",
                    event.getId().toString(), event.getOrganizationId().toString(),
                    Long.toString(event.getSequenceNumber()), event.getActorUserId().toString(),
                    event.getAction(), event.getResourceType(), event.getResourceId(),
                    event.getRequestId().toString(), event.getMetadata(), event.getPreviousHash());
        }
        if (event.getHashVersion() == 2) {
            return String.join("\n",
                event.getId().toString(), event.getOrganizationId().toString(),
                Long.toString(event.getSequenceNumber()), event.getActorUserId().toString(),
                event.getAction(), event.getResourceType(), event.getResourceId(),
                event.getRequestId().toString(),
                event.getIpAddress() == null ? "" : event.getIpAddress(),
                event.getUserAgent() == null ? "" : event.getUserAgent(),
                event.getMetadata(), event.getPreviousHash());
        }
        // v3 adds the project the event concerns to the signed fields.
        return String.join("\n",
                event.getId().toString(), event.getOrganizationId().toString(),
                event.getProjectId() == null ? "" : event.getProjectId().toString(),
                Long.toString(event.getSequenceNumber()), event.getActorUserId().toString(),
                event.getAction(), event.getResourceType(), event.getResourceId(),
                event.getRequestId().toString(),
                event.getIpAddress() == null ? "" : event.getIpAddress(),
                event.getUserAgent() == null ? "" : event.getUserAgent(),
                event.getMetadata(), event.getPreviousHash());
    }

    private boolean constantTime(String left, String right) {
        return left != null && right != null && crypto.constantTimeEquals(left, right);
    }
}
