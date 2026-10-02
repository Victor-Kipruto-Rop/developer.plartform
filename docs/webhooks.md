# Webhook Platform

**This phase is partially built.** The signature scheme and the retry policy are
complete and tested. The endpoint, subscription and delivery machinery is not yet
written. This document describes what exists and is explicit about the gap,
because a webhook platform that is half-implemented and presented as finished is
worse than one that is obviously incomplete.

## What is built

| Component | State |
| --- | --- |
| HMAC signing | **Complete and tested** |
| Timestamp validation | **Complete and tested** |
| Replay protection (server side) | **Complete and tested** |
| Signature versions | **Complete and tested** |
| Retry policy with backoff + jitter | **Complete and tested** |
| Endpoint management | Not built |
| Subscriptions | Not built |
| Secret storage and rotation | Not built |
| Delivery worker | Not built |
| DLQ | Not built |

## Signing

Every delivery carries a header:

```
PesaGuard-Signature: t=1712345678,v1=4e82adaac59e5124b4db704461ccbe38476325eec69fe913625889eccadd6772
```

`v1` is HMAC-SHA256 over `"{version}.{timestamp}.{body}"`.

### Canonical example

These values are cross-checked against two independent implementations (Python
and Node) on every test run, so the documented example and the implementation
cannot drift apart. An integrator copying them will get a signature that verifies.

```
secret:  whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw
body:    {"id":"evt_01","type":"payment.succeeded","data":{"id":"pay_123","amount":5000,"currency":"KES"}}
time:    1712345678
header:  t=1712345678,v1=4e82adaac59e5124b4db704461ccbe38476325eec69fe913625889eccadd6772
```

The body must be signed **byte for byte as sent**. A trailing newline, a
different key ordering, or a re-serialized payload all produce a different
signature — verify against the raw request body, never a re-encoded copy.

Python, for reference:

```python
signed = f"v1.{epoch_seconds}.{body}"
sig = hmac.new(secret.encode(), signed.encode(), hashlib.sha256).hexdigest()
header = f"t={epoch_seconds},v1={sig}"
```

### Why the timestamp is signed

A signature over the body alone is a bearer token for "this payload is genuine".
An attacker who captures one delivery can replay it forever, and the receiver has
no way to tell. Binding the timestamp means a captured delivery stops verifying
once it falls outside the tolerance window.

### Verification

Two independent checks, both of which must pass:

1. **Freshness** — the timestamp must be within five minutes of now.
2. **Authenticity** — the HMAC must match, compared in constant time.

Drift is compared **absolutely**. A timestamp far in the future is as suspicious as
one far in the past; an "older than" check alone would accept a signature from a
clock hours ahead.
## Retry policy

Exponential backoff with **full jitter**: the delay is drawn uniformly from
`[0, min(cap, base * 2^attempt)]`, not set to exactly `base * 2^attempt`.

### Why jitter is not cosmetic

Without it, every endpoint that failed at the same moment retries at the same
moment. After a PesaGuard incident, every integrator in the country retries in
lockstep, and the retry storm takes down the platforms they are retrying against.
That is a self-inflicted outage caused by the very mechanism meant to help
recovery. Full jitter also stops many deliveries to one endpoint from
synchronising with each other.

The cap bounds the tail. Without it, `2^attempt` eventually exceeds any delay a
client will wait and the delivery is abandoned before its attempt budget is spent.
The shift is capped at 30 so a large attempt number cannot overflow into a
negative duration and schedule a retry in the past.

### Which responses are retried

| Status | Retried | Why |
| --- | --- | --- |
| 2xx | no | Success |
| 408, 429 | **yes** | About timing, not about the request |
| 410 | no | Endpoint is gone; permanent by definition |
| Other 4xx | no | The request itself is wrong; retrying changes nothing |
| 5xx | **yes** | The receiver may recover |

Retrying an identical request that was rejected wastes the attempt budget and
hammers the receiver. Transport failures (DNS, timeout, connection) always retry:
they say nothing about the receiver's intent.

## What has NOT been built

- **Endpoint management.** No `WebhookEndpoint` entity, no create/update/delete/
  activate/deactivate, no verification handshake. The `webhook:read` and
  `webhook:write` RBAC permissions and the `webhooks:*` API scopes exist from
  Phase 07, but nothing enforces them.
- **Subscriptions.** No event-type filtering, environment binding, or payload
  version selection.
- **Secret storage and rotation.** The signature scheme takes a secret string;
  nothing yet generates, stores (encrypted at rest) or rotates it. Rotation is
  subtle: in-flight retries signed with the previous secret must still verify
  during a grace window, or a rotation silently breaks deliveries already queued.
- **Delivery.** No queue, worker, HTTP client, delivery tracking or status model.
- **DLQ.** Nothing yet.

## Security requirements not yet discharged

- **SSRF.** A webhook endpoint URL is **user-supplied**, which makes it a
  server-side request forgery vector. Phase 08 built `OutboundTargetGuard` for
  exactly this (loopback, private ranges, cloud metadata, IPv4-mapped IPv6, DNS
  rebinding via name resolution, 44 tests). **The delivery worker must call it on
  every send, and at endpoint creation time.** This is the single most important
  outstanding requirement in this phase and it is not yet wired.
- **Secret handling.** Signing secrets must be encrypted at rest, never logged,
  and returned exactly once at creation and rotation.
- **Timeout.** Deliveries need a hard per-request timeout so one hanging receiver
  cannot hold a worker thread indefinitely.

## Verification status

- The signing and retry policy are covered by **43 passing tests**, including a
  cross-implementation check.
- **Cross-validation is performed and passing.** `WebhookSignatureTest` executes
  two independent reference implementations
  (`src/test/resources/webhook_signature_reference.py` and `.js`), both written
  from the documented rule rather than ported from the Java source, and asserts
  that all three agree on the canonical vector *and* on verification outcomes for
  fresh, stale and forged headers. It skips only if an interpreter is absent, so a
  build that ran it is distinguishable from one that did not.
- **NOT TESTED against PostgreSQL.** No migration for webhook tables has been
  written, so there is nothing schema-level to verify yet.
- **No live HTTP delivery has been performed.** The scheme is validated across
  three implementations, but it has not been exercised over a real HTTP request
  by a third-party receiver, and no external security review has been done.
- **Uncommitted** — the working directory is entirely untracked and CI has never
  run. In CI the cross-check will only run if Python or Node is installed on the
  runner; if neither is present the test skips, so the pinned canonical vector
  remains the durable guarantee.

Comparison is constant-time. `String.equals` short-circuits on the first differing
byte, which leaks how much of a guess was correct and turns brute force from a
2^128 search into a byte-at-a-time oracle.

### Honest limitation: replay within the window

A replayed delivery **inside** the five-minute window still verifies. Timestamp
validation alone cannot detect it, and pretending otherwise would be a false
assurance. Full protection requires the receiver to dedupe on the delivery id,
which is why the delivery id must be sent as a separate header. This is a
receiver-side responsibility and must be documented to integrators.