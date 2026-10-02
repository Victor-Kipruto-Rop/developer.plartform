# Rate Limits & Quotas (Phase 13)

Status: **ALGORITHMS AND MODEL ONLY — NOT ENFORCED.** Everything here is
implemented and unit-tested, but nothing calls the limiter yet. No request is
checked, no policy is stored, and the counter store is in-process only.

Per your decision, Redis was **not** added. See "Why no Redis" below.

## What exists

| Component | File | State |
|---|---|---|
| Scopes | `ratelimit/domain/RateLimitScope.java` | Complete, tested |
| Algorithms enum | `ratelimit/domain/RateLimitAlgorithm.java` | Complete |
| Token bucket | `ratelimit/domain/TokenBucket.java` | Complete, tested |
| Sliding window | `ratelimit/domain/SlidingWindow.java` | Complete, tested |
| Quota periods | `ratelimit/domain/QuotaPeriod.java` | Complete, tested |
| Policy | `ratelimit/domain/RateLimitPolicy.java` | Complete |
| Decision | `ratelimit/domain/RateLimitDecision.java` | Complete |
| Store SPI | `ratelimit/application/RateLimitCounterStore.java` | Complete |
| In-memory store | `ratelimit/application/InMemoryRateLimitCounterStore.java` | Complete, tested |
| Composition | `ratelimit/application/RateLimitService.java` | Complete, tested |

## Why no Redis

You asked for Redis-backed distributed limiting. Redis is not present in this
project at all — no dependency, no container, no config — and you chose to build
the algorithms against a store interface with an in-memory implementation
instead.

`RateLimitCounterStore` is the seam. It exposes `consume(policy, key, now)` as
a **single indivisible operation** returning a decision, rather than a get and a
put. That shape is what a Redis implementation will need, because a
read-then-write limiter has a race: two concurrent requests both read "1
remaining" and both proceed, doubling the effective limit. Encoding atomicity
into the interface now means the distributed implementation cannot quietly
forget it.

**The in-memory store is single-node only.** Three nodes behind a limit of
100/min admit up to 300/min. It is correct for one instance and for tests.

## Scopes

`API_KEY`, `PROJECT`, `ENVIRONMENT`, `ORGANIZATION`, `ENDPOINT`, `IP`, `USER`,
`WEBHOOK`.

Scopes **compose**: a request is allowed only if every applicable limit allows
it. That is what lets one runaway API key be stopped without throttling its
organization.

Evaluation is ordered most-specific-first, and the **strictest decision is the
one reported**. With an endpoint limit of 10/min and an organization limit of
1000/min, the caller is told 10 — the number they can actually act on.

Consumption is **not rolled back** when a later scope denies. The request did
consume the earlier scopes' capacity, and refunding would let a caller exceed a
limit by making requests that are themselves refused.

## Algorithms

**Token bucket** — tokens accrue continuously to a burst ceiling. For protecting
a backend from a spike: the ceiling bounds the worst case while the sustained
rate stays predictable. This is what `environment_limits.requests_per_minute` and
`burst_requests` already describe.

**Sliding window** — an exact rolling count of request timestamps. For daily and
monthly quotas, where "how much have I used" is the honest question. A token
bucket would let a client spend a full burst at midnight and again at midnight
the next day, which is not what a daily quota means to anyone.

A sliding window is used rather than a fixed one because a fixed window is
defeatable at its boundary: spend the full allowance at 00:59 and again at
01:01 and a caller gets twice the limit within two seconds. `SlidingWindowTest`
asserts that case directly.

Cost: memory proportional to traffic inside the window, which is why every
touch prunes.

## Quotas

`QuotaPeriod.DAILY` and `MONTHLY`, anchored to **UTC calendar boundaries** rather
than a rolling 24 hours. A quota that resets at midnight UTC can be planned
around; one that resets 24 hours after a customer's first request cannot be
displayed meaningfully.

Periods are separate counters rather than one counter that resets, so a reset
can never lose a concurrent write and the previous period stays readable.

Months go through `LocalDate` because `Instant` supports neither truncation nor
addition by months. **This codebase already hit that bug once**, in the Phase 12
usage rollup, where every monthly bucket threw at runtime.

## Developer visibility

`RateLimitDecision` carries all four values together — limit, remaining, reset,
and retry-after — so the decision and the response headers cannot drift apart.
A caller told "you have 0 left" but not "come back in 12 seconds" has been told
an unusable half-truth.

An unconfigured limit reports `-1` rather than `0`, so a client can tell
*unlimited* from *exhausted*. Conflating them makes an unlimited integration
believe it has been throttled.

## Two bugs found by the tests

Both were in the in-memory store, both are fixed, both now have regression tests.

**A fresh counter started empty**, refusing the first request of every window.
That presents as a broken API rather than as a limit.

**The lock object was replaced on every write.** The map held a placeholder
`Object()` and each write swapped in a new state object, so two threads could
hold *different monitors for the same key* and check-and-consume was not atomic.
The original concurrency test passed on a single run — by luck, not correctness.
`InMemoryRateLimitCounterStoreTest.concurrentConsumptionIsAtomic` now asserts an
exact count under 300 racing threads.

## Not implemented

- **Nothing is wired to requests.** No filter, no interceptor, no enforcement.
- **No policies are stored.** There is no table, repository, or CRUD, so limits
  cannot be configured. `RateLimitPolicy` is an in-memory value object.
- **No quota service.** `QuotaPeriod` exists; nothing consumes it.
- **No headers, controller, or OpenAPI route.** Nothing emits
  `X-RateLimit-*` or `Retry-After`.
- **No Redis.** Single-node only, per the decision above.
- **No test coverage of the fail-open path against a real failing store** beyond a
  stub.
- **No metrics.** A limiter nobody can see is a limiter nobody can tune.

## Decisions to revisit

- **Fail-open on store failure.** A broken store lets everything through and
  logs an error. The alternative takes the API down. For a payment platform,
  fail-open on rate limits is usually right, but it does mean an outage silently
  removes protection. This should be a per-policy decision, not a global one.
- **Exact sliding window over an approximation.** Memory grows with traffic in
  the window. A production Redis implementation will almost certainly want the
  two-bucket approximation, which is cheaper but slightly less exact. The
  interface supports it; the current implementation does not use it.
- **No webhook-specific semantics.** A `WEBHOOK` scope exists, but delivering to
  a webhook and serving an API request have different failure costs and deserve
  separate policies rather than one shared counter.