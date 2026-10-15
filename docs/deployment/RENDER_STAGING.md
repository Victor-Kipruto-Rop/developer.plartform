# Render staging setup

This repository's `render.yaml` describes a staging deployment for the Java
developer-platform backend. It does not move or copy the Core API repository.
The Core API remains its own Render service and should be added to the same
Render workspace and project.

## Before syncing the Blueprint

1. Push the reviewed `deploy/render-staging` branch to this repository, or
   merge the reviewed change before creating the Blueprint. The Blueprint's
   service currently deploys from `deploy/render-staging`; change it to `main`
   only after the change has been merged there.
2. Confirm the existing Core API Render service is reachable at
   `https://api.pesaguard.co.ke` and has
   `POST /internal/v1/developer-api-keys/sync` enabled.
3. Confirm the API deployment accepts the same
   `PESAGUARD_PIPELINE_KEY_SYNC_SECRET` that will be set on the developer
   platform. Do not put that secret in GitHub, this file, or a Blueprint value.
4. In the Render Dashboard, create or select the PesaGuard project and add this
   repository as a Blueprint. Keep the developer backend and Core API in the
   same workspace/project; the Blueprint provisions the developer backend's
   database and Redis separately.
5. Review the service region and current Render prices before confirming the
   Blueprint sync. The Blueprint selects Frankfurt and paid plans; it can incur
   charges. Render does not offer a Kenya region, so use synthetic/test data
   only unless the data-residency requirement is formally changed and approved.

## Secrets to provide in Render

The Blueprint generates independent credential, audit, and operator HMAC keys.
Provide these secret values through Render's initial Blueprint prompts:

- `PESAGUARD_JWT_PRIVATE_KEY`: Base64-encoded DER PKCS#8 RSA private key.
- `PESAGUARD_JWT_PUBLIC_KEY`: matching Base64-encoded DER X.509 RSA public key.
- `PESAGUARD_PIPELINE_KEY_SYNC_SECRET`: a random secret of at least 32 bytes;
  configure exactly the same value on the Core API service.
- `RESEND_API_KEY`: a key for the approved email provider.
- `SUPPORT_TICKET_EMAIL`: a monitored support mailbox.

For an existing Blueprint, Render ignores `sync: false` values on later syncs;
add or update these through the service's environment settings instead.
Never copy secrets into source files or share them in chat.

## Safety defaults and integration

- Registration, production access, billing, and Kafka messaging are disabled.
- Automatic deploys are disabled; deploy the staging branch manually after
  reviewing the Blueprint and providing the required secrets.
- API-key synchronization is required and fails closed. If the Core API URL or
  shared secret is unavailable, new API keys will not be issued.
- Database migrations run through the application's Flyway configuration. Take
  a backup before any migration on a non-disposable database.
- `/health/ready` checks database readiness. Verify Redis and API-key sync
  separately during the staging smoke test.
- The Java service is a backend only. The React frontend in this repository is
  documented as an unconnected preview and must not be represented as a working
  developer portal until it is integrated and tested against these endpoints.
- This staging setup does not enable or verify live payment processing.

## After the Blueprint is created

1. Set `PESAGUARD_ALLOWED_ORIGINS` and the WebAuthn origin/RP ID to the final
   HTTPS developer portal origin.
2. Add the custom domain in Render only after the portal UI is ready. Configure
   DNS as instructed by Render and verify TLS.
3. Confirm Render reports the service healthy and test database migrations,
   Redis-backed throttling, registration (in an approved test configuration),
   email delivery, and API-key synchronization end to end.
4. Verify the API-key sync request is authorized and does not expose raw API
   secrets in logs. Test failure handling by making the sync destination
   unavailable in staging and confirming key issuance fails closed.
5. Keep production API access and payments disabled until the separate release
   and provider verification gates pass.

To roll back an application release, redeploy the previous image/commit. Do not
delete the managed database as a rollback method; preserve and restore it only
through the approved backup procedure.
