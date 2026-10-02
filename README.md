# PesaGuard developer-platform backend

This repository contains the standalone Java 21/Spring Boot backend for the PesaGuard developers portal.

## Implemented vertical slice

- registration, login, logout, and current-session flows;
- explicit organization selection at login for multi-organization accounts;
- revocable opaque bearer sessions stored as SHA-256 hashes, with organization session-TTL and maximum-session limits;
- persistent account/IP login throttling;
- organization lifecycle (create, update, verify, suspend, restore, disable, soft delete, ownership transfer);
- organization membership management, invitations, and immutable membership history;
- per-organization security settings (auth methods, session TTL/idle/max sessions, credential policy, IP allowlist, security event types);
- permission-based authorization (RBAC): a 22-permission catalog, six built-in roles, organization-scoped custom roles with no-privilege-escalation and separation-of-duties enforcement;
- project lifecycle with metadata, ownership transfer, project members, and project settings;
- tiered environments (DEVELOPMENT/SANDBOX/STAGING/PRODUCTION) with configuration, limits, append-only history, and forward-only promotion;
- time-boxed production access requests with mandatory independent review;
- tenant-scoped projects and sandbox-only environments;
- tenant-guarded API-key issue/list/revoke flows;
- API-key lifecycle (CREATED/ACTIVE/SUSPENDED/REVOKED/EXPIRED) with rotation lineage, per-key IP allowlists, usage metadata, and append-only key history;
- HMAC-protected API-key storage and one-time secret disclosure;
- append-only, organization sequence-locked, HMAC hash-chained audit events;
- liveness/readiness endpoints, Actuator, correlation IDs, and safe error envelopes.

OAuth/OIDC, MFA, billing, production environments, payment execution, financial simulation, webhook ingestion, and API-key consumption by resource APIs are intentionally outside this slice.

## Local prerequisites

- JDK 21;
- PostgreSQL 17 (or Docker Compose);
- Gradle wrapper `backend/gradlew` with Gradle 9.8.0.

Copy `.env.example` to a local, ignored environment file and provide independent 32-byte Base64 keys. Never commit runtime secrets.

```powershell
$env:DATABASE_URL = 'jdbc:postgresql://localhost:5432/pesaguard_developer_platform'
$env:DATABASE_USERNAME = 'postgres'
$env:DATABASE_PASSWORD = '<local database password>'
$env:PESAGUARD_CREDENTIAL_HMAC_KEY = '<openssl rand -base64 32>'
$env:PESAGUARD_AUDIT_HMAC_KEY = '<different openssl rand -base64 32>'
$env:PESAGUARD_ALLOWED_ORIGINS = 'http://localhost:3000'
```

Run from `backend`:

```powershell
.\gradlew.bat :compileJava
.\gradlew.bat test
.\gradlew.bat bootJar
```

The Testcontainers integration suite uses PostgreSQL and is marked to skip when Docker is unavailable. Unit tests do not require Docker.

## Runtime configuration

The authoritative configuration names are in `backend/src/main/resources/application.yml`. The service uses `DATABASE_URL`, `DATABASE_USERNAME`, and `DATABASE_PASSWORD`; the Compose file maps its `POSTGRES_*` variables to those application variables. Registration is disabled by default. Set `PESAGUARD_REGISTRATION_ENABLED=true` only in an approved local/test environment.

Flyway owns the schema. Hibernate runs in `validate` mode. Do not use `ddl-auto=update` for a shared or financial database.

## API contract

The checked-in contract is `backend/src/main/resources/static/openapi/pesaguard-developer-v1.yaml` and is served at `/openapi/pesaguard-developer-v1.yaml`. It documents the endpoints implemented in this slice, including the one-time API-key response and the tenant-scoped resource rules.

## Documentation

- `docs/architecture.md` — module boundaries, transaction model, and tenant isolation;
- `docs/security.md` — credential handling and security controls;
- `docs/rbac.md` — permissions, built-in and custom roles, production access review;
- `docs/environment-limits.md` — per-environment limits and where each is enforced;
- `docs/operations.md` — health, deployment, backup, and incident notes.

## Multi-organization login

Login accepts an optional `organizationId`.

- With exactly one active membership the organization is derived from that membership.
- With more than one active membership and no `organizationId`, login fails with `409 ORGANIZATION_SELECTION_REQUIRED`; the server never silently picks a tenant.
- A supplied `organizationId` that is not an active membership of the account fails with `401 INVALID_CREDENTIALS`, so selection cannot be used to probe membership.

Suspended, disabled, and deleted organizations are not loginable; their sessions are revoked when the lifecycle status changes.

## Verification status

Evidence from this workspace (2026-10-01 run):

- `backend\gradlew.bat --version` verified Gradle 9.8.0 on Temurin JDK 21.0.12.1.
- `:compileJava` completed successfully.
- `:compileTestJava` completed successfully.
- `test` completed successfully: 69 tests discovered, 59 unit tests passed, and 10 PostgreSQL Testcontainers tests were skipped because the local Docker daemon is unavailable.
- `bootJar` completed successfully; the generated artifact is `backend\build\libs\pesaguard-developer-platform.jar` (57.2 MB).
- The OpenAPI contract parses as valid YAML and every `$ref` in it resolves (47 references checked).
- The boot-JAR smoke test reached Flyway database initialization with the corrected security bean graph; startup then failed as expected because the smoke URL pointed to an intentionally unavailable PostgreSQL port.

NOT VERIFIED in this environment:

- Flyway execution of `V1` and `V2` against a real PostgreSQL instance, and Hibernate `ddl-auto=validate` agreement with the migrated schema.
- The 10 Testcontainers integration tests covering the Phase 02 HTTP flows (organization lifecycle, invitations, membership history, security settings, multi-organization login selection, cross-tenant isolation).
- Docker image build, deployment, and production behavior.

Treat the service as an unverified development artifact until those checks run in an environment with PostgreSQL/Docker and reviewed secrets.
