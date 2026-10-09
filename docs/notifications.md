# Developer notifications

The notification subsystem writes durable events in the same transaction as
their source changes, then processes them asynchronously. Request handlers do
not wait for SMTP or another external delivery provider. The worker stores inbox
entries and per-channel delivery attempts, and retries eligible email failures
through the configured SMTP relay. Notifications are product messages for
developers; operational alerts for platform staff are out of scope.

## Events and emitters

`NotificationType` defines the credential, webhook, usage, production-access,
and security event catalog. Events receive a severity and can carry related
resource metadata and an internal action path. Current emitters cover API-key
lifecycle changes, production-access transitions, recorded security signals,
exhausted webhook delivery failures, support-ticket lifecycle changes, and
per-environment usage quota thresholds. Queue events accept an idempotency key;
support-ticket events use stable per-ticket keys, and usage alerts are
deduplicated by environment, alert type, and UTC hour. Other catalog entries
may not yet have an event producer.

Queue work is claimed under database row locking, retried with backoff, and
moved to a dead-letter state after repeated processing failures. Delivery state
is stored independently per channel. A successful in-app notification is not
sent again when its email is retried. The scheduled worker locks each
notification before retrying due channels and records every attempt.

## Inbox and preferences

Authenticated users can list their organization-scoped inbox, filter by
category, severity, or unread state, mark one or all notifications read, and
read or update channel preferences. Inbox results are newest first and
paginated. Reads are scoped to both the active organization and the
authenticated user. Read notifications are deleted immediately, matching the
inbox's existing retention behavior.

In-app delivery is always enabled. Email cannot be disabled for categories with
mandatory events: credential revocation, production suspension or revocation,
and security alerts. Preference updates that would silence those events are
rejected. Only channels with registered transports are reported as available.
Browser/mobile push, SMS, and notification webhooks remain unavailable until
providers are configured; no phone number is collected for the unconfigured SMS
channel. The OpenAPI document describes the inbox, response envelopes, enums,
filters, pagination, and preference payloads.

## Email delivery

`SmtpEmailTransport` sends plain-text messages through Spring Mail. It rejects
invalid recipient addresses, reports delivery failures for retry, and avoids logging
recipient addresses, subjects, message bodies, or provider response text.

SMTP is configured with `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`,
`MAIL_PASSWORD`, and `MAIL_SMTP_AUTH`. STARTTLS is enabled and required by
default; local development may disable it for Mailpit. Connection, read, and
write timeouts are configurable with `MAIL_SMTP_CONNECTION_TIMEOUT_MS`,
`MAIL_SMTP_READ_TIMEOUT_MS`, and `MAIL_SMTP_WRITE_TIMEOUT_MS`.

To deliver all developer-platform emails through Resend instead, set
`PESAGUARD_EMAIL_PROVIDER=resend`, `RESEND_API_KEY`, and
`PESAGUARD_MAIL_FROM` to a sender verified in Resend. The Resend adapter uses
the provider's HTTPS API and does not require SMTP credentials. Supply the API
key through the ignored `.env` or deployment secret store.

Docker Compose starts Mailpit with SMTP and inbox ports bound to loopback:

- SMTP: `localhost:1025`
- Inbox: `http://localhost:8025`

Production deployments must provide either an authenticated SMTP relay with
STARTTLS or a Resend API key, and set a verified sender address before enabling
public signup.

## Remaining work

- Emitters do not yet cover every catalog type; credential expiry, traffic-spike
  detection, and additional webhook conditions need producers.
- The SMTP relay and public signup configuration must be exercised in each
  deployment environment.
- Failed mandatory mail delivery is persisted, but no separate operator alert
  escalates prolonged or exhausted notification failures.
- Browser push, mobile push, SMS, and outbound notification webhooks require
  provider configuration, subscription or destination management, and explicit
  user opt-in before they can be enabled.
- The new queue migration and its PostgreSQL locking/idempotency behavior still
  require a PostgreSQL-backed integration run.
