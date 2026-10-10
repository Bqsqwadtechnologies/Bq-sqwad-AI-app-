# Cloudflare Worker backend migration

The Cloudflare Worker is now the compatibility gateway and contains a native Cloudflare implementation for the core authenticated AI endpoints:

- `GET /health`
- `POST /ai`
- `POST /ai/stream`
- `POST /ai/vision`

The remaining API surface is intentionally still proxied to the existing Firebase Functions backend until each subsystem is migrated and verified.

## Required Cloudflare secrets

Set these as Worker secrets. Never commit them to GitHub and never paste them into chat.

1. `BQ_FIREBASE_SERVICE_ACCOUNT_JSON`
   - Full JSON of the Firebase/Google service account used for server-side Firestore access.
   - It must have permission to read/write the BQ Sqwad AI Firestore database.
2. `FIREBASE_WEB_API_KEY`
   - The Firebase Web API key for project `bqsqwadai`.
   - This is used only to validate Firebase ID tokens through Firebase Authentication.
3. `OPENAI_API_KEY`
   - The server-side OpenAI API key.

Later migration phases will add provider secrets for Gemini, Tavily, Google Play, Meta, YouTube, TikTok and X as needed.

## User-only setup

These steps require access to the Cloudflare dashboard and Google/Firebase credentials, so they cannot safely be performed from this repository connection:

1. Open the Cloudflare Worker `bq-sqwad-ai-app`.
2. Open **Settings -> Variables and Secrets**.
3. Add the three secrets above as encrypted secrets.
4. Save/deploy the Worker from the Cloudflare dashboard.
5. Do not remove Firebase Functions, Firestore, Auth, or existing service-account permissions yet.

## Migration rule

Firebase remains the data/auth source during the migration. Each subsystem is migrated to Cloudflare first, tested against the existing data model, and only then is the corresponding Firebase Function considered removable.

No GitHub Actions workflow is being created or intentionally triggered by this migration.


### Worker environment variables
- `SUPER_ADMIN_EMAIL`: owner/admin email used by the Worker for super-admin authorization. Keep the value in Cloudflare Worker environment configuration; never commit it as a secret or hardcoded value.


### Required Worker secrets / variables
Configure these in Cloudflare, never in source control:
- `BQ_FIREBASE_SERVICE_ACCOUNT_JSON`
- `FIREBASE_WEB_API_KEY`
- `OPENAI_API_KEY`
- `GEMINI_API_KEY`
- `TAVILY_API_KEY`
- `SUPER_ADMIN_EMAIL`
- `ALLOWED_ORIGINS`
- Social OAuth secrets used by `social.js`
- Google Play verification secrets used by the canonical Play verification path


## 2026-10-07 migration pass
- Restored native Firestore imports in the Worker, including firestoreQuery for `/history`.
- Migrated `/play/catalog` to the Worker and Firestore-backed BQ catalog projection.
- Migrated `/play/verify` to the native Google Play verification implementation.
- `functions/` remains reference-only; the retired Cloud Function URL is no longer present in `cloudflare-worker/src/index.js`.
- BQ Sqwad AI catalog remains the app-facing catalog. Google Play is used only for purchase verification/acknowledgement.
- Full legacy route parity remains open for the old organization, business, admin, generation, companion, learning, TruthGuard, passkey and payment routes; these require complete Worker ports before the migration is finished.

- Added native `/native/catalog`, `/entitlement`, `/entitlements`, and `/usage` Firestore routes using the existing BQ data model.
- Removed the Worker proxy function and retired Cloud Function URL from the active router. Unmigrated legacy endpoints now return an explicit `route_not_migrated` response instead of forwarding to the retired backend.
- Added native Live Camera `/ai/vision` provider/credit path using the existing Worker provider gateway and `capability_live_camera` authorization.
- Removed the retired-backend workspace gate from TikTok publishing; Worker checks the Firestore business workspace directly.

## Native migration continuation — 2026-10-07
- Added native Worker route modules for Companion, Learning, TruthGuard and core Organization operations.
- Added native Business workspace/CRM/extended-record/automation handlers.
- Added native Organization production configuration/source/scene/destination/state/health/AI-live handlers.
- Added native Organization scheduled-task handlers.
- Added native admin test-mode, plan-catalog, plan-status/popular, admin control/config/advanced-option, Business test-session and Business plan-status handlers.
- Added a native Organization plan/catalog/access module; its router wiring remains pending verification.
- Removed all active proxyUpstream/retired Firebase Cloud Function forwarding from cloudflare-worker/src/index.js.
- The Worker still intentionally reports route_not_migrated for specialized surfaces that have not yet been ported, rather than silently calling the retired backend.

## End-to-end migration audit — 2026-10-07
- Re-audited `functions/index.js` route patterns against the active Worker router.
- Legacy route-pattern audit: 172 unique old routes; 177 Worker route patterns; 0 old route patterns missing from the Worker router after accounting for path-prefix routing.
- Added native Worker modules/routes for YouTube Live, live chat, protected-content policy, Organization wallet/live billing, Business advanced AI/integrations/enterprise, runtime records, passkeys, admin security, notifications, X actions, Organization social, Companion memory, realtime sessions, account deletion, Contact Sales, app configuration, plans/credits/usage, Play subscription management, media-rights, admin layout/staff/runtime/provider/social controls, and Google Play-only payment compatibility routes.
- Paystack payment endpoints are explicitly retired and return a Google Play billing response; they do not forward to the old backend.
- `cloudflare-worker/package.json` syntax checking now covers all native migration modules.
- Active Worker code contains no `proxyUpstream` reference and no retired Firebase Cloud Function URL.
- Training-asset binary upload URL issuance still requires an explicit Firebase Storage signing configuration; the metadata/finalize routes are native and no retired backend fallback exists.
- Provider secrets remain Cloudflare Worker secrets rather than being written into Firestore by the Worker admin API.


## Backblaze B2 training-asset storage — 2026-10-07

Firebase Storage is not used for Business training-asset binaries because the project must remain off Firebase Blaze.

The native Worker now uses Backblaze B2 for the existing `businessTrainingAssets` flow:

- `POST /business/training-assets/upload-url` — authenticates the Firebase user, checks Business manager access, creates the existing `businessTrainingAssets/{assetId}` metadata record with `upload_pending`, and returns a temporary B2 upload URL plus upload authorization token.
- `POST /business/training-assets/finalize` — verifies the B2 object exists and its size matches the Firestore metadata, then changes the existing asset to `uploaded` and maintains the existing `businessWorkspaces/{workspaceId}.trainingAssets` mirror.
- `GET /business/training-assets/download?assetId=...` — returns a short-lived B2 download authorization for an uploaded private asset.
- `DELETE /business/training-assets?assetId=...` — removes the B2 object and existing Firestore metadata/mirror entry.

Required Cloudflare Worker secrets:

- `B2_APPLICATION_KEY_ID`
- `B2_APPLICATION_KEY`
- `B2_BUCKET_ID`

The B2 application key should be restricted to the dedicated training-assets bucket and granted only the capabilities required for listing, uploading, downloading authorization, and deleting training assets. Do not commit B2 credentials to GitHub or place them in Android.

The existing Firestore collection and metadata model are preserved. The storage provider changes from Firebase Storage to B2; Firebase Auth and Firestore remain the source of identity/metadata.

Backblaze's current documentation confirms that B2's first 10 GB of storage is free, and its Native API supports temporary upload authorization and private-file download authorization. The free allowance is an account-level allowance, not 10 GB per workspace.


## 2026-10-10 — Account deletion grace period

- Normal clients can no longer invoke immediate account deletion through `DELETE /account`; the Worker returns HTTP 405.
- `POST /account/schedule-deletion` records a 30-day pending-deletion deadline, and `POST /account/cancel-deletion` restores the account to active status before that deadline.
- `GET /account/deletion-status` supports the Android cancellation notice.
- A daily Cloudflare Cron Trigger at 03:00 UTC processes due records in `userAccountStatus` and reuses the shared permanent-deletion function.
- The Worker service account must have the required Firebase Authentication user-delete permission in addition to its existing Firestore permissions. The cron handler retries failed deletion jobs by returning the record to `pending_deletion` and records a short failure reason.
- The Android account deletion flow now schedules deletion and signs out only after the server confirms the schedule. The Settings screen shows the returned deadline and a cancel action when the user signs back in.

## Follow-up hardening — October 2026

- Admin API routes now require a server-checked step-up session that expires after 30 minutes. The identity bootstrap and security setup/unlock routes remain available so the owner can establish the first factor; existing factors are required to replace or extend configured security.
- The Worker secret readiness endpoint reports configured/missing status only and never returns secret values. It now includes the super-admin allowlist, origin configuration, admin TOTP encryption key, Play Billing, and social provider keys.
- Required for authenticator/TOTP setup: provision BQ_SECRET_ENCRYPTION_KEY as a Cloudflare Worker secret containing exactly 64 hexadecimal characters (32 random bytes). Never commit its value to Git or put it in the Android app. Password-only step-up does not require this encryption key.
- The scheduled account-deletion job now orders pending accounts by their deadline, uses a Firestore update-time precondition before marking an account as deleting, preserves a cancellation that wins the race, and deletes the Firebase Auth account before removing associated Firestore data so missing Auth IAM permission does not wipe data.
- wrangler.jsonc declares the daily 03:00 UTC cron. The cron only becomes active after the updated Worker configuration is deployed.
- Settings AI feature cards now open an explanation and a Try it action that navigates through the existing workspace access gate.
- Live Voice now has a connection watchdog and preserves its terminal error state after the foreground service stops.
- Validation still requires a manual Validate Main and Debug APK workflow run against the latest main; a previous successful Release APK run is from an older commit and does not verify these changes.
- The service account in BQ_FIREBASE_SERVICE_ACCOUNT_JSON must also have the Firebase Authentication IAM permission `firebaseauth.users.delete` for scheduled account deletion to finish; without it, the job retries safely and leaves Firestore data intact.
- Failed scheduled deletions are moved to a 24-hour retry deadline so a failed first page cannot starve other expired accounts; the app reflects the new retry date and cancellation remains available while Firebase Auth still exists.
