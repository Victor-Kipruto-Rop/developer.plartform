# Operations Runbook — Production & Enterprise Readiness (Phase 22)

Status: **manifests and CI/CD complete and YAML-validated. Monitoring rules and DR
procedures are specified but untested — no cluster, database, or broker has ever
been stood up.**

## Deployment

`infrastructure/kubernetes/deployment.yaml` and `networkpolicy.yaml`
(9 documents, all parse-validated).

**Probes are split on purpose.** Liveness hits `/health/live` and does *not* check
PostgreSQL or Redis. Readiness hits `/health/ready` and does. If liveness
depended on the database, a database outage would restart every pod and turn a
degradation into a full outage.

`startupProbe` is generous (30 x 5s) because Flyway runs at startup and a slow
first migration must not be killed as a hang.

**Resources:** requests 500m/768Mi, limits 2000m/1536Mi. Deliberately unequal —
memory is not compressible, and a limit equal to the request risks an OOMKill on
a GC spike.

**Graceful shutdown:** `terminationGracePeriodSeconds: 45`, a 10s `preStop` sleep
so endpoint propagation removes the pod before it stops accepting, and
`maxUnavailable: 0` so a rollout never dips below the PDB minimum.

**PDB:** `minAvailable: 2` of 3 replicas. `minAvailable: 1` would permit a
two-pod simultaneous loss.

**HPA:** 3-20 replicas at 70% CPU. Scales up fast (30s window, +100%/30s) and
down slowly (600s window, -25%/60s) — thrashing removes capacity exactly when
traffic returns.

## Network isolation

Default-deny first, then explicit allows. Order matters: a namespace with only
allow rules still permits everything not named.

This is what makes the Phase 19 separation more than an application convention.
`/internal/**` is reachable only from the admin namespace, and the public
developer Ingress targets 8080 only. Egress is restricted to DNS, PostgreSQL, and
Redis, so a compromised pod cannot reach arbitrary internal services.

## CI/CD

Full pipeline: compile -> unit -> **integration** -> security -> SAST ->
dependency scan -> container scan -> build -> staging -> smoke ->
**approval** -> production.

Three steps are worth calling out:

- **The suite asserts database tests actually ran.** If anything is skipped, CI
  fails rather than reporting green. A silent skip is how "migrations never
  executed" survives for months.
- **Staging smoke tests assert a 401/403** from an unauthenticated protected
  route. A **200 there means authorization is not enforced**, so the test fails on
  a security improvement — deliberately.
- **Production requires environment approval** and rolls back automatically if
  verification fails. A failed verification is not a reason to leave the bad
  revision serving traffic.

## Monitoring

`/actuator/prometheus` is now exposed (Micrometer registry added). Without it the
surface was health and metrics JSON that nothing can alert on.

| Signal | Alert condition |
|---|---|
| API availability | Any pod down > 2 min |
| API latency | p95 > 500 ms for 5 min |
| API errors | 5xx rate > 1% for 5 min |
| Auth failures | 401/403 spike (brute force) |
| Authorization failures | Any cross-tenant hit — **page** |
| Credential operations | Any revoke — **page** |
| Usage ingestion | `droppedCount()` > 0 for 5 min — **usage is incomplete** |
| Rate-limit operations | Fail-open (Redis down) — **page** |
| DB health | Hikari pending > 0 sustained = pool exhaustion |
| Redis health | `isAvailable()` false — **page** |
| JVM health | Heap > 85% sustained |

Webhook success/failure/latency, event lag, and DLQ size are requested but have
**no source**: the webhook delivery system and event emission were never built
(see `remaining-work.md`). Those metrics cannot exist until the subsystems do,
and a dashboard of permanently-zero series would be worse than none — it looks
healthy.

## Disaster recovery

| Data | Strategy | Status |
|---|---|---|
| PostgreSQL | Base backup daily + WAL archiving for PITR | **Not configured** |
| Restore test | Restore to scratch monthly, verify row counts | **Not done** |
| Redis | No persistence by design; counters are recomputable | Implemented |
| Redpanda | Retention + tiered storage | **N/A — no broker** |
| Event replay | Re-emit from `event_deliveries` | **N/A — nothing emits** |
| Configuration | Secret manager, values versioned | **Not set up** |

### Restore testing is the item that matters

A backup that has never been restored is a hypothesis, not a backup. The procedure
that must exist before production:

1. Provision a scratch PostgreSQL from the base backup.
2. Replay WAL to a named point in time (`recovery_target_time`).
3. Verify row counts per table and re-run `verifyChain` on the audit table — it
   is the one table where silent corruption is invisible to a row count.
4. Record the measured RTO.

None of this has been done. **There is currently no evidence the platform can be
restored at all**, and that is the single largest operational risk.

### Redis recovery, specifically

Redis holds only rate-limit counters and is configured `appendonly no` on
purpose: every value is recomputable, and persisting them would cost I/O on the
hot path to protect state that is *correct to lose* on restart. A restart must not
hand a flooder a fresh budget mid-window; the consequence is bounded to one
window of excess traffic, which is the right trade against the alternative.

## Known operational gaps

- **No alerting is wired.** The table specifies what should alert; nothing does.
  Prometheus rules and Alertmanager config are not written.
- **No dashboards.**
- **No DR has been rehearsed.** The largest risk here.
- **Rate limiting is still not wired to requests.** It fails open if enabled, and
  a failing-open limiter must page, not log.
- **Log aggregation is unspecified.**