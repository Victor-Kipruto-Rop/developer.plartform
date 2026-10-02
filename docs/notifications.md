# Developer Notifications (Phase 16)

Status: **DOMAIN, RETRY LOGIC, AND SCHEMA ONLY — NO EVENT RAISES ANYTHING, AND
EMAIL DOES NOT SEND.** Nothing calls `NotificationService`, there is no controller,
and no SMTP transport exists. **V16 has never been executed.**

## Scope

Developer-platform notifications only. Platform-operational mail is deliberately
out of scope.

All 18 events from the phase are defined in `NotificationType`:

| Category | Events |
|---|---|
| Credential | `API_KEY_CREATED`, `API_KEY_ROTATED`, `API_KEY_REVOKED`, `CREDENTIAL_EXPIRING` |
| Webhook | `WEBHOOK_ENDPOINT_FAILING`, `WEBHOOK_REPEATED_DELIVERY_FAILURE`, `WEBHOOK_ENDPOINT_DISABLED` |
| Usage | `QUOTA_WARNING`, `QUOTA_EXCEEDED`, `TRAFFIC_SPIKE` |
| Production | `PRODUCTION_REQUEST_RECEIVED`, `PRODUCTION_REVIEW_STARTED`, `PRODUCTION_APPROVED`, `PRODUCTION_REJECTED`, `PRODUCTION_SUSPENDED` |
| Security | `SUSPICIOUS_ACTIVITY`, `CREDENTIAL_COMPROMISE`, `SESSION_REVOCATION` |

## Email does not send, and says so

There is no Spring Mail dependency, no SMTP configuration, and no provider in
this build. `UnconfiguredEmailTransport` therefore **reports every attempt as
failed** rather than pretending to have delivered.

This is the most important decision in the phase. An implementation that logged
the mail and returned success would make a revoked-credential notification look
delivered to the developer, to the audit log, and to any test asserting on
delivery state — while no email had left the building. That removes the evidence
the message was never sent, and the user finds out from a failed production
payment instead.

Wiring a real transport means replacing that one class. The retry policy,
per-channel state, and preference handling are all transport-agnostic.

## Preferences: honoured, but an unsafe one is refused

Per your decision, a user's stated preference is respected, and a preference
that would silence a security-critical event is **rejected at the point it is
set** rather than silently overridden at send time.

Silently ignoring a request is worse than refusing it: it leaves the user
believing they are covered when they are not.

**Mandatory events**, which email cannot be switched off for:

- `API_KEY_REVOKED` — the credential the user depends on stopped working
- `PRODUCTION_SUSPENDED` — a live production grant was withdrawn
- `SUSPICIOUS_ACTIVITY`, `CREDENTIAL_COMPROMISE`, `SESSION_REVOCATION`

Note that `API_KEY_REVOKED` lives in the **CREDENTIAL** category, not SECURITY.
Safety is therefore a property of the *type*, not the category — checking only
category names would have let the most important credential event be silenced.
`NotificationCategory.hasMandatoryEvents()` is derived from the catalog rather
than declared, so a new mandatory event cannot be forgotten there.

`WEBHOOK` and `USAGE` remain fully user-controllable: those are ordinary product
mail.

**In-app can never be disabled.** It is the durable record, and a notification
nobody can read again did not happen. A database CHECK backs the application
rule.

There are two lines of defence: the preference is refused when written, and
`channelsFor` re-adds email for mandatory events when read — so a row written by
any other route still cannot silence a revocation.

## Delivery state

`PENDING -> IN_PROGRESS -> DELIVERED | RETRY_SCHEDULED | FAILED | SUPPRESSED`

**Channels are tracked independently.** A notification whose email failed but
whose in-app record succeeded is not "failed" overall; treating it as such would
hide a delivered message and retry it forever.

**Suppressed channels are recorded, not omitted.** A user who later asks "why was
I not told?" can be shown they had opted out, rather than finding no record.

`RETRY_SCHEDULED` is distinct from `FAILED` so a transient network fault is not
recorded as permanent loss. A `FAILED` notification is visible and retained —
quietly losing "your key was revoked" is the worst outcome this subsystem has.

## Retries

Exponential backoff with **full jitter**, mirroring the Phase 11 webhook policy.
Jitter is not cosmetic: a storm of expiring credentials would otherwise retry in
lockstep and reproduce the storm that caused the failures.

Five attempts, against the webhook policy's eight. A notification the user has
not received after an hour is not going to arrive usefully, and retrying longer
mostly produces mail that lands late and out of order.

`ceilingFor` is overflow-safe: a large attempt number would otherwise overflow the
multiplication and produce a negative duration — an instant retry storm.

Discretionary events (`QUOTA_WARNING`) are not retried. Security and
credential events are.

## Not implemented

- **Nothing raises a notification.** No credential, webhook, usage, production, or
  security code path calls the service. The catalog is inert.
- **No controller, no endpoints, no OpenAPI.** Users cannot see or configure
  anything.
- **Email is not wired.** See above.
- **No retry worker.** `RETRY_SCHEDULED` states are computed but nothing scans for
  them and re-attempts. Retries are currently a one-attempt-and-give-up path.
- **No OpenAPI or RBAC permission** for notification preferences.
- **No read/unread handling** in the service, though the column and index exist.
- **V16 has never executed** — every constraint is unverified.

## Decisions to revisit

- **The dedup index includes `created_at`**, so it only collapses identical
  timestamps. It will not stop a revoked key producing one notification per
  subsequent request, which is the case it most needs to handle. A time-bucketed
  or subject-scoped key is needed.
- **Mandatory email is a policy, not a user choice.** A user who genuinely wants no
  email cannot have it for six of the eighteen events. That is the agreed
  trade-off, but it should be visible in the UI rather than discovered by a
  failed preference update.
- **No escalation.** If a mandatory email fails permanently, nothing alerts an
  operator that a security notification was lost.