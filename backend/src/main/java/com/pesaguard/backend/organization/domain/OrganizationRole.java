package com.pesaguard.backend.organization.domain;

import java.util.List;
import java.util.Set;

import com.pesaguard.backend.rbac.domain.Permission;

/**
 * Built-in organization roles. These are defined in code and cannot be edited or
 * deleted; custom roles live in the database.
 *
 * <p>Least privilege is deliberate and worth stating explicitly:
 * <ul>
 *   <li>SECURITY reviews production access and rotates/revokes credentials, but
 *       cannot change organizations or projects.</li>
 *   <li>ANALYST is read-only, including usage and audit, and cannot be given
 *       production access review.</li>
 *   <li>DEVELOPER may create projects and environments and manage their own
 *       credentials, but cannot delete a project or review production access.</li>
 *   <li>No built-in role holds both {@code production_access:request} and
 *       {@code production_access:review}. OWNER and SECURITY can review;
 *       ADMIN and DEVELOPER can request. Keeping them apart is what makes
 *       separation of duties enforceable rather than aspirational.</li>
 * </ul>
 */
public enum OrganizationRole {
    OWNER,
    ADMIN,
    DEVELOPER,
    SECURITY,
    ANALYST,
    VIEWER;

    public Set<Permission> permissions() {
        return switch (this) {
            case OWNER -> Set.of(
                    Permission.ORGANIZATION_READ, Permission.ORGANIZATION_UPDATE,
                    Permission.PROJECT_READ, Permission.PROJECT_CREATE,
                    Permission.PROJECT_UPDATE, Permission.PROJECT_DELETE,
                    Permission.ENVIRONMENT_READ, Permission.ENVIRONMENT_CREATE,
                    Permission.ENVIRONMENT_UPDATE,
                    Permission.CREDENTIAL_READ, Permission.CREDENTIAL_CREATE,
                    Permission.CREDENTIAL_REVOKE, Permission.CREDENTIAL_ROTATE, Permission.CREDENTIAL_UPDATE,
                    Permission.WEBHOOK_READ, Permission.WEBHOOK_CREATE,
                    Permission.WEBHOOK_UPDATE, Permission.WEBHOOK_DELETE,
                    Permission.OAUTH_APPLICATION_READ, Permission.OAUTH_APPLICATION_CREATE,
                    Permission.OAUTH_APPLICATION_UPDATE, Permission.OAUTH_APPLICATION_ROTATE,
                    Permission.OAUTH_APPLICATION_SUSPEND, Permission.OAUTH_APPLICATION_REVOKE,
                    Permission.SANDBOX_READ, Permission.SANDBOX_CREATE, Permission.SANDBOX_UPDATE,
                    Permission.SANDBOX_ACTIVATE, Permission.SANDBOX_SUSPEND, Permission.SANDBOX_RESET,
                    Permission.SANDBOX_EXECUTE, Permission.SANDBOX_DELETE,
                    Permission.USAGE_READ, Permission.AUDIT_READ,
                     Permission.SECURITY_READ, Permission.SECURITY_CONTROL,
                     Permission.PRODUCTION_ACCESS_REVIEW);
            case ADMIN -> Set.of(
                    Permission.ORGANIZATION_READ, Permission.ORGANIZATION_UPDATE,
                    Permission.PROJECT_READ, Permission.PROJECT_CREATE,
                    Permission.PROJECT_UPDATE, Permission.PROJECT_DELETE,
                    Permission.ENVIRONMENT_READ, Permission.ENVIRONMENT_CREATE,
                    Permission.ENVIRONMENT_UPDATE,
                    Permission.CREDENTIAL_READ, Permission.CREDENTIAL_CREATE,
                    Permission.CREDENTIAL_REVOKE, Permission.CREDENTIAL_ROTATE, Permission.CREDENTIAL_UPDATE,
                    Permission.WEBHOOK_READ, Permission.WEBHOOK_CREATE,
                    Permission.WEBHOOK_UPDATE, Permission.WEBHOOK_DELETE,
                    Permission.OAUTH_APPLICATION_READ, Permission.OAUTH_APPLICATION_CREATE,
                    Permission.OAUTH_APPLICATION_UPDATE, Permission.OAUTH_APPLICATION_ROTATE,
                    Permission.OAUTH_APPLICATION_SUSPEND, Permission.OAUTH_APPLICATION_REVOKE,
                    Permission.SANDBOX_READ, Permission.SANDBOX_CREATE, Permission.SANDBOX_UPDATE,
                    Permission.SANDBOX_ACTIVATE, Permission.SANDBOX_SUSPEND, Permission.SANDBOX_RESET,
                    Permission.SANDBOX_EXECUTE, Permission.SANDBOX_DELETE,
                    Permission.USAGE_READ, Permission.AUDIT_READ,
                    Permission.SECURITY_READ,
                    Permission.PRODUCTION_ACCESS_REQUEST);
            case DEVELOPER -> Set.of(
                    Permission.ORGANIZATION_READ,
                    Permission.PROJECT_READ, Permission.PROJECT_CREATE, Permission.PROJECT_UPDATE,
                    Permission.ENVIRONMENT_READ, Permission.ENVIRONMENT_CREATE,
                    Permission.ENVIRONMENT_UPDATE,
                    Permission.CREDENTIAL_READ, Permission.CREDENTIAL_CREATE,
                    Permission.CREDENTIAL_ROTATE,
                    Permission.WEBHOOK_READ, Permission.WEBHOOK_CREATE,
                    Permission.WEBHOOK_UPDATE, Permission.WEBHOOK_DELETE,
                    Permission.OAUTH_APPLICATION_READ, Permission.OAUTH_APPLICATION_CREATE,
                    Permission.OAUTH_APPLICATION_UPDATE, Permission.OAUTH_APPLICATION_ROTATE,
                    Permission.OAUTH_APPLICATION_SUSPEND, Permission.OAUTH_APPLICATION_REVOKE,
                    Permission.SANDBOX_READ, Permission.SANDBOX_CREATE, Permission.SANDBOX_UPDATE,
                    Permission.SANDBOX_ACTIVATE, Permission.SANDBOX_SUSPEND, Permission.SANDBOX_RESET,
                    Permission.SANDBOX_EXECUTE, Permission.SANDBOX_DELETE,
                    Permission.USAGE_READ,
                    Permission.PRODUCTION_ACCESS_REQUEST);
            case SECURITY -> Set.of(
                    Permission.ORGANIZATION_READ,
                    Permission.PROJECT_READ,
                    Permission.ENVIRONMENT_READ,
                    Permission.CREDENTIAL_READ, Permission.CREDENTIAL_REVOKE, Permission.CREDENTIAL_ROTATE, Permission.CREDENTIAL_UPDATE,
                    Permission.WEBHOOK_READ,
                    Permission.USAGE_READ, Permission.AUDIT_READ,
                    Permission.SECURITY_READ, Permission.SECURITY_CONTROL,
                    Permission.PRODUCTION_ACCESS_REVIEW);
            case ANALYST -> Set.of(
                    Permission.ORGANIZATION_READ,
                    Permission.PROJECT_READ,
                    Permission.ENVIRONMENT_READ,
                    Permission.USAGE_READ, Permission.AUDIT_READ,
                    Permission.SECURITY_READ);
            case VIEWER -> Set.of(
                    Permission.ORGANIZATION_READ,
                    Permission.PROJECT_READ,
                    Permission.ENVIRONMENT_READ);
        };
    }

    /** Roles that may administer other members' roles. */
    public boolean managesRoles() {
        return this == OWNER || this == ADMIN;
    }

    /** Permissions that must never be held together by one actor. */
    public boolean violatesSeparationOfDuties(Set<Permission> granted) {
        return granted.contains(Permission.PRODUCTION_ACCESS_REQUEST)
                && granted.contains(Permission.PRODUCTION_ACCESS_REVIEW);
    }

    public static List<OrganizationRole> all() {
        return List.of(values());
    }
}
