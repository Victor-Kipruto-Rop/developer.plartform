# Production Gate Register

Rule applied: **every checklist item is either implemented and tested, or
explicitly documented as intentionally out of scope.**

Current: **607 tests, 0 failures, 10 skipped. 16 migrations, none applied.**

The distinction that determines pass/fail is no longer "does it exist" — it is
"if it does not exist, was that a decision or an omission." This register makes
every such decision explicit.

---

## A. Implemented and tested

| § | Area | Evidence |
|---|---|---|
| 3 | Organizations + membership | Invitations, lifecycle, history, audit |
| 4 | Projects | Full lifecycle, membership, settings |
| 6 | RBAC | 6 roles, permission registry, assignment, separation-of-duties enforcement |
| 9 | OAuth | Authorization code + PKCE, refresh rotation, replay detection, family revocation |
| 11 | Token management | Families, introspection, reuse detection |
| 12 | API scopes | Registry, versioning, restriction, deprecation, environment awareness |
| 13 | API catalog | Registry, versions, required scopes, OpenAPI metadata |
| 14 | API explorer security | SSRF guard — **44 tests** (private IP, localhost, cloud metadata, DNS rebinding, redirects) |
| 8 | Credential security | HMAC hashing, prefix, rotation, IP allowlist, last-used, lifecycle |

## B. Implemented, NOT enforced at runtime

Built and unit-tested, but nothing invokes them. **These are omissions, not
scope decisions** — the checklist calls for the behaviour, and it does not happen.

| § | Item | Why it does not run |
|---|---|---|
| 5 | Sandbox/production isolation | No credential filter; a sandbox key is never actually refused against production |
| 7 | Multi-tenancy | Rules and composite FKs exist; **never executed** (no database) |
| 8 | Emergency/global revocation | Domain rules exist; no entry point |
| 18/19 | Usage + analytics | Ingestion runs; `api_key_id` always null, so credential analytics return nothing |
| 20/21 | Rate limits + quotas | Redis-backed algorithms tested; **no filter calls them — nothing is limited** |
| 24 | Security centre | Domain + persistence; no detectors, no controller |
| 25 | Security events | 8 event types defined; **no detector raises any** |
| 26 | Notifications | 18 events defined; nothing emits; no controller; email reports every send failed |
| 29 | Audit | Chain + trigger + catalog; **no call site uses the catalog**; operator actions unaudited |
| 30 | Internal admin | Separation built and tested; **no controller** |
| 38 | Request limits | Same limiter gap as §20 |

## C. Intentionally out of scope — DECLARED

These are deliberate. The checklist itself states the boundary, and it is
correct: **this backend manages developer-facing capabilities and does not
implement transaction, payment, fraud, or reconciliation business logic**, which
live in `pesaguard_backend_pipeline` and sibling services.

| § | Item | Reason |
|---|---|---|
| 1 | Business domain engines | Declared out of scope by the checklist; owned by the core platform |
| 12 | Transaction/fraud/reconciliation *logic* | Scopes are managed here; the engines are not |
| 30 | A general PesaGuard admin console | Explicitly "not the general PesaGuard administration platform" |

**Redis persistence (§1).** Declared: counters only, `appendonly no`, because
every value is recomputable and correct to lose on restart. A restart must not
hand a flooder a fresh budget mid-window.

## D. Missing — NOT out of scope, and not built

**These fail the gate.** They are inside the declared scope of this backend, and
they do not exist.

| § | Item | Status |
|---|---|---|
| 1, 17 | **Redpanda/Kafka** — producer, consumer, groups, topics, retry topics, DLQ, replay | 0 references. Not a dependency, no container |
| 10 | **Service Accounts** | 0 references. Named pillar |
| 16 | **Webhook delivery** — queue, worker, timeout, DLQ, manual retry, replay | Zero outbound HTTP clients in the codebase |
| 17 | **Event emission** | Registry exists; nothing emits |
| 23 | **Developer Verification** | Only the production-access request workflow exists |
| 24 | Recent auth/activity views | No controller |
| 27/28 | SDK/CLI persistence + publishing | Domain + schema only; no repositories |
| 31 | Structured logs, tracing, alert wiring | **Logging + tracing now done.** Alerting rules not written |
| 32 | Redpanda health | Nothing to check |
| 33 | Circuit breakers | 0 — no resilience4j |
| 35 | Contract tests, E2E, Redis/Redpanda integration | One integration class, never run |
| 36/37 | Load and failure testing | Requires infrastructure that has never run |
| 41 | Backups, PITR, restore test, RPO/RTO | **Scripts written, never executed** |
| 40 | CI pipeline execution | Pipeline complete, **never run** |
| 1 | Cursor pagination, email verification state | Absent |
| 2 | Account deletion workflow | Absent |

## E. Structural blocker

**The platform has never executed against PostgreSQL.** All 16 migrations are
unapplied; the 10 skipped tests are Testcontainers tests; there is one
integration class.

This is why §7, §5 and §42 cannot pass regardless of implementation state — the
gate requires *tested*, and nothing integration-level has been tested. It also
makes §41 unprovable: a restore script that has never run against real data is
not a recovery capability.

---

## Register summary

| | Count |
|---|---|
| Implemented and tested | 8 areas |
| Implemented, not enforced | 10 areas |
| Declared out of scope | 3 areas |
| **Missing (gate failures)** | **14 areas** |
| Blocked on infrastructure | 1 (systemic) |