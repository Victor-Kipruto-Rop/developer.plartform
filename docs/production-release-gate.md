# Production Release Gate

This checklist is an operational gate, not a claim that the platform is production-ready. A checked box requires a dated record or CI/deployment evidence.

## 1. Source and CI

- [ ] CI is green for the exact commit being released: backend tests, PostgreSQL/Testcontainers integration tests, frontend type-check/tests/build, container build and scan, and CodeQL.
- [ ] No required test suite was skipped. The only permitted skip is the explicitly documented optional pipeline-target smoke test when no target is configured.
- [ ] High/Critical findings from dependency, filesystem, and image scans are resolved or covered by a documented, time-limited risk acceptance.
- [ ] The published image digest is immutable and the SBOM artifact is retained.
- [ ] The release is tagged with a version such as `v1.2.3`; production deployment is not triggered by an ordinary branch push.

## 2. Database and recovery

- [ ] A fresh PostgreSQL instance can apply the complete Flyway migration chain successfully.
- [ ] Integration tests cover constraints, foreign keys, transaction rollback, idempotency, concurrent writes, and tenant boundaries.
- [ ] Production backups are encrypted, access-controlled, and retained according to a documented policy.
- [ ] A restore drill has completed successfully and its recovery time and recovered data point are recorded.
- [ ] Migration rollback/forward-recovery steps are documented and rehearsed.

## 3. Identity, tenancy, and credentials

- [ ] Cross-tenant authorization tests cover every protected organization, project, environment, API-key, usage, webhook, audit, support, and export endpoint.
- [ ] Email verification, MFA challenge expiry/replay, password reset, session expiry/revocation, refresh-token rotation, and rate limits have automated tests.
- [ ] API-key creation, one-time disclosure, scope enforcement, expiration, rotation, revocation, and downstream synchronization are verified end-to-end.
- [ ] Production API credentials cannot be activated before an authorized Go-Live approval and a valid, unexpired grant for the exact project/environment.
- [ ] Operator credentials and routes are isolated from developer API credentials and public access.
- [ ] No real credentials or private keys are committed to the repository, image, logs, or build artifacts.

## 4. Runtime and infrastructure

- [ ] Protected GitHub environment `staging` has only staging-scoped Kubernetes credentials and verified deployment/monitoring URLs.
- [ ] Protected GitHub environment `production` has required reviewers, least-privilege production-scoped credentials, and a documented approver.
- [ ] Kubernetes NetworkPolicies have been verified against the actual cluster CNI and namespace labels; ingress, DNS, database, Redis, metrics, and required outbound connections work as intended.
- [ ] TLS, domain routing, CORS, WebAuthn RP ID/origins, cookie security, CSRF protections where applicable, and proxy headers match the real HTTPS portal origin.
- [ ] Readiness/liveness probes, resource limits, graceful shutdown, autoscaling, and rollback have been verified in the target cluster.
- [ ] PostgreSQL and Redis credentials are supplied through the deployment secret mechanism and rotated using a documented procedure.

## 5. Integrations and operations

- [ ] Production email is delivered by the configured relay/provider with authenticated transport and a verified sender domain; verification/reset links use the production HTTPS URL.
- [ ] Webhook signature verification, timestamp/replay checks, idempotency, retry backoff, dead-letter handling, and operator replay are tested against a controlled receiver.
- [ ] Prometheus is collecting the correct service and alerts have been exercised for availability, elevated errors, database/Redis failure, saturation, and failed webhook delivery.
- [ ] Logs and traces contain correlation/request IDs but do not expose passwords, API keys, session tokens, verification codes, webhook secrets, or personal data unnecessarily.
- [ ] Incident response, escalation, maintenance mode, customer communication, and rollback runbooks have named owners.
- [ ] The team has explicitly verified which payment and transaction features are implemented. Do not advertise transaction execution, live provider processing, or event ingestion unless those paths are implemented and acceptance-tested.

## 6. Staging acceptance and production authorization

- [ ] The exact release candidate has passed the staging rollout and application readiness checks.
- [ ] Staging checks confirm database and Redis health, metrics collection, public health endpoints, and unauthenticated denial of protected and internal routes.
- [ ] Representative functional, negative, authorization, and load tests passed with results attached to the release.
- [ ] Backup restore, failed deployment, and rollback drills passed.
- [ ] Product owner and operations/security owner approved the release against the evidence above.
- [ ] Production deployment used the same scanned immutable image digest that passed staging.
- [ ] Post-deployment smoke tests and monitoring review passed; a rollback decision and owner are documented.

## Current audit note

The repository's operations runbook and remaining-work register state that a successful deployment to protected staging/production, the full PostgreSQL migration/integration run, backup/restore, email delivery, and live provider checks have not been verified in the repository workspace. Treat those as open until evidence is attached. A passing source CI run alone is not production certification.
