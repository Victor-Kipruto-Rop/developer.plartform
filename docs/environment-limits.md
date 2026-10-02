# Environment limits

Each environment owns one `environment_limits` row. Defaults are created with the
environment: 600 requests/minute, 100 burst requests, 5 API keys, 20 credentials,
and a 60-minute credential rotation interval.

## Where each limit is enforced

| Limit | Enforced in | Notes |
| --- | --- | --- |
| `maxApiKeys` | Application | Enforced at API-key issuance. |
| `maxCredentials` | Application | Enforced at credential creation. |
| `credentialRotationIntervalMinutes` | Application | Enforced at credential rotation. |
| `requestsPerMinute` | **Not yet enforced** | Stored and exposed via the API only. Enforcement belongs at the ingress/gateway; the platform does not currently meter requests. |
| `burstRequests` | **Not yet enforced** | Same as above. |

This distinction is deliberate and is not a claim of coverage: two of the five
limits are configuration data until a gateway or a metering component consumes
them. Do not configure rate limits here expecting the service to enforce them.

## Tier isolation

Limits are stored per environment, never per project or organization. Changing the
limits of DEVELOPMENT does not affect SANDBOX, STAGING, or PRODUCTION, and the
unique constraint on `(project_id, type)` guarantees a project cannot hold two
environments of the same tier to sidestep a limit.