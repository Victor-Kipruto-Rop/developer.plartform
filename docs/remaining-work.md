# Remaining Work — Developer Platform

Verified against the repository. **598 tests, 0 failures, 10 skipped**, 16
migrations, 14 controllers, 42 repositories, 4 scheduled jobs.

---

## 0. The blocking item

**Nothing in this platform has ever executed against PostgreSQL.** All 16
migrations are unrun. Every constraint, foreign key, trigger, check constraint,
seed, and Hibernate mapping is unverified. The 10 skipped tests are all
Testcontainers tests; there is exactly **one** integration test class.

This is not one task among many. Until a database runs, every other item below
is unproven rather than unfinished, and the ordering matters: fixing schema or
service code before validating the migrations risks building on a broken
foundation.

**First task: run `docker compose up` and execute the full suite with Docker
available.** Everything else is cheaper to do afterwards.

---

## 1. Systems that do not exist

### Webhook delivery (largest single gap)
Phase 11 built signing and retry policy. **The delivery system itself was never
built.** No outbound HTTP client exists anywhere in the codebase — no
`RestClient`, `WebClient`, `HttpClient`, or `URLConnection`.

Missing: dispatcher, HTTP sender, durable queue, DLQ *producer* (the status value
and index exist in V11 with nothing writing to them), replay tooling, manual
retry API.

### Event emission (Phase 11)
Event types, subscriptions, and delivery records exist. **Nothing emits.** No
event emitter, no schema validation against registered JSON schemas, no
subscription matching, no dispatch worker.

### Production access — request detail fields (Phase 14)
The largest gap against its own spec. **Every content field is absent**: no
application details, organization details, intended API usage, requested scopes,
requested limits, integration information, or security information. A request
carries only a free-text `reason`.

Also missing: **no provisioning**. Activation is a manual API call — nothing
creates a production credential when it succeeds. And **no enforcement**: nothing
checks `isActiveGrant` before serving, so ACTIVE does not unlock anything.

### Credential rotation + application suspension wiring (Phase 15)
Rules and permissions exist; the actions are not implemented.

### Security event detection (Phase 15)
All eight event types are defined and recordable. **No detector raises any of
them.** No revoked-credential usage, token replay, scope abuse, or abnormal-usage
detection. `AuthSession` was never updated to write `device_label` / `last_ip`.

### Notification emission (Phase 16)
All 18 events defined. **Nothing calls the service.** No controller, no OpenAPI,
and **no retry worker** — `RETRY_SCHEDULED` is computed but nothing scans for it,
so retries are currently one-attempt-and-give-up.

### Email transport (Phase 16)
`UnconfiguredEmailTransport` reports every send as **failed**. This is deliberate
and correct — a transport that reported success without sending would make a
revoked-credential notification look delivered while no mail left the building.
Wiring a real provider means replacing that one class.

### SDK/CLI persistence + publishing (Phase 17)
Domain and schema exist. **No repositories, no JPA entities, no controller, no
publishing workflow.** Checksums are entered by hand rather than written by the
release pipeline — the integrity guarantee currently depends on a human copying
64 hex characters correctly.

### Platform administration controllers (Phase 19)
The separation is built and tested. **No controller** — none of the eleven
operator capabilities is reachable.

---

## 2. Unwired code (exists, unreachable, or inert)

| Area | State |
|---|---|
| Rate limiting | Built and tested. **No filter calls it** — nothing is limited. No policy storage, no CRUD, no headers. |
| Usage analytics | Ingestion + rollup wired. **No `ApiKeyAuthenticator` filter exists**, so `api_key_id` is always null and credential-usage analytics returns nothing. |
| Security centre | Domain + persistence. No controller, no detectors. |
| Audit catalog | `AuditAction` declared and tested, but **no call site uses it** — services still pass hand-written strings. No call site passes a real `projectId`. |
| Notification preferences | Model exists. No endpoint to read or change them. |

---

## 3. Unverified-by-construction

- **Redis rate limiting** — Lua scripts compile but **have never executed**.
- **All 18 migrations** — never applied.
- **Audit chain v1/v2/v3** — three canonical forms; v1 and v2 have never verified against a stored row.
- **`@ConditionalOnProperty` store selection** — both stores exist; only the in-memory path is exercised in tests.
- **Second filter chain (Phase 19)** — no test drives a developer session at `/internal/**` through the real chain.

---

## 4. Correctness gaps worth fixing before scale

- **Rate limiter is fail-open globally.** A Redis outage silently removes
  protection for every customer. Should be per-policy.
- **Notifications drop on overflow.** Bounded buffer, counted but no DLQ.
  Losing a "your key was revoked" is the worst outcome in that subsystem.
- **Audit chain verification is never scheduled.** `verifyChain` exists but
  nothing calls it, so tampering is noticed only when someone looks.
- **Operator actions are not audited.** Phase 18 needs an `actorUserId` an
  operator does not have. An operator revoking a customer's credential is exactly
  what the audit log exists to capture.
- **V8 and V13 are missing from the migration sequence.** Adding them later
  needs out-of-order handling or renumbering.
- **98 `saveAndFlush` call sites.** Only the audit one was analysed and changed.
  Each forces a round trip inside a transaction; the rest are unreviewed.

---

## 5. Untested because unverifiable here

Query plans, index usage, connection utilization, locking, transaction duration
under load, webhook delivery throughput, queue depth, consumer lag, event
throughput, and DLQ behaviour. All require infrastructure that has never run.
`docs/reliability-and-performance.md` has the dependency-ordered plan.

---

## 6. Not started

- **OpenAPI coverage.** Several controllers exist with no spec entry (usage,
  security events, production-access review/activate/suspend/revoke/history).
- **Retention policies.** `audit_events`, `api_request_events`, and
  `security_events` grow without bound. Recompute-based rollups make raw-event
  pruning unsafe until the coarsest window closes.
- **CI.** Never run. The working tree is untracked.
- **Observability.** No metrics, no alerts. `droppedCount()` on the usage
  recorder, chain-verification failure, and notification exhaustion are all
  observable only by reading logs.

---

## Suggested order

1. **Run the database.** Execute all migrations; run the suite with Docker. Every
   other item is unproven until this passes.
2. **Fix what the run breaks.** Expect real failures — this is the first
   execution, not a regression.
3. **Webhook delivery + event emission.** The two largest absent subsystems, and
   both are prerequisites for the platform being useful.
4. **Wire the inert code**: rate-limit filter, usage credential attribution,
   audit catalog adoption, notification emission + retry worker.
5. **Phase 14 request fields + provisioning + enforcement.**
6. **Phase 19 controllers + operator audit.**
7. **Persistence for security centre, notifications, SDK/CLI.**
8. **Then and only then: load, plan, and scale testing.**

Steps 3+ can be parallelised across people. Step 1 cannot, and everything after
it is cheaper.