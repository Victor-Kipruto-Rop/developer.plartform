# Suggestions & feedback API

The Java backend serves developer feedback at `/api/v1/feedback` and operator
workflows at `/internal/feedback`. Developer routes require a developer session
JWT; operator routes use the separate internal operator token chain and require
`SUPPORT_READ` for reads and `SUPPORT_RESOLVE` plus an operator reason for
mutations.

Developer-facing feedback responses use the public `FB-...` reference, never the
database UUID. The typed API envelope is `{ "data": ..., "requestId": ... }`.
List data uses `items`, `page`, `pageSize`, `totalItems`, and `totalPages`.
Feedback creation requires `Idempotency-Key`; repeating the same request for the
same organization and user returns the original record, while reusing that key
for different content is rejected.

Projects and environments are accepted only when they belong to the authenticated
organization, and an environment must belong to the selected project. User and
organization identifiers are always taken from the authenticated principal.
Developer list, item, and comment queries are scoped to both authenticated
organization and user. Operator reads and changes require explicit operator
capabilities. Public comment endpoints only select `PUBLIC` comments; internal
notes are stored separately and never included in developer DTOs, notifications,
or outbox event payloads.

Feedback records, immutable activity events, audit details, and transactional
outbox events commit together. Kafka delivery remains governed by the existing
outbox configuration (disabled by default). Developer notification work is
queued through the existing asynchronous notification pipeline; no SMTP call is
made while handling the API request.

The service applies database-backed fixed-hour limits of 10 creations and 30
comments per user, enforces title/description/comment bounds, uses a fixed
pagination size ceiling, and strips query strings and fragments from page/route
context. Context fields are rejected if they resemble credentials and user text
passes through the existing sensitive-data redactor.

## Attachments and delivery gaps

Attachments are intentionally not exposed by this backend. There is no configured
private object-storage provider or malware-scanning service in this deployment.
No public upload directory, public URL, or unverified storage fallback is created.
Enable attachments only after private storage, authenticated downloads, byte/MIME
and count limits, checksum validation, and scanning are available.

Operator notifications for new feedback are represented by transactional
`feedback.created` outbox events. A feedback-specific operator notification
consumer and email template are not present; the API does not synchronously send
or claim delivery of those messages. Operator assignment currently targets
configured queue/team values; there is no operator directory from which to
validate or resolve individual assignees.
