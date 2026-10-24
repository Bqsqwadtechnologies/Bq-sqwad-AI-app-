# BQ Sqwad AI — Release Runbook

## Current status

Source changes only. Nothing has been deployed. Do not publish this batch as fully verified until the blockers below are resolved and the owner completes the device checks.

## Android app repository

Repository: `Bqsqwadtechnologies/Bq-sqwad-AI-app-`, branch `main`.

### Worker files changed in this work

- `cloudflare-worker/src/billing-core.js` — inspected; no source change in this batch.
- `cloudflare-worker/src/token-billing-native.js` — invalid canonical policy falls back to website defaults; missing OpenAI token rates use website reference rates; config errors use a safe public error.
- `cloudflare-worker/src/index.js` — live pricing defaults, server-timestamp heartbeat accounting, admin billing doctor data, admin-only chat self-test, generated-media deletion cleanup, sanitized billing errors.
- `cloudflare-worker/src/firebase.js` — service-account Storage upload and prefix-deletion helpers; Storage API scope added.
- `cloudflare-worker/src/generation-native.js` — generated/edited images are uploaded server-side when possible; response includes `imageUrl` while retaining `imageBase64`.
- `cloudflare-worker/test/billing-core.test.js` — added tests for missing/invalid defaults and zero-duration billing.

### Android files changed

- `app/src/main/java/com/bqsqwad/ai/core/NativeCameraPreview.kt`
- `app/src/main/java/com/bqsqwad/ai/core/BqLifecycleNotifications.kt`
- `app/src/main/java/com/bqsqwad/ai/core/LiveMediaForegroundService.kt`
- `app/src/main/java/com/bqsqwad/ai/core/WorkspaceApiClient.kt`
- `app/src/main/java/com/bqsqwad/ai/ui/theme/Theme.kt`
- `app/src/main/java/com/bqsqwad/ai/ui/BQSqwadApp.kt`
- `app/src/main/java/com/bqsqwad/ai/ui/AdminControlSurface.kt`

### Owner deployment steps

1. Review and deploy the Worker source changes above using the existing Cloudflare Worker deployment process. Do not change secrets; the helper accepts the optional secret name `BQ_FIREBASE_STORAGE_BUCKET` and otherwise uses the app's configured Firebase Storage bucket.
2. Deploy the repository's current `storage.rules` and `firestore.rules` to Firebase. **Rules committed to this repository do nothing until deployed.**
3. Do not run a GitHub Actions workflow on behalf of the owner.
4. Run the consolidated owner test list in the task prompt on a physical Android device and the published website.

## Website repository

Repository: `bqsqwadai/bqsqwadai-b3064406`, branch `main`.

### Website files changed in this work

- `src/components/chat/ChatWindow.tsx` — renders legacy `imageUrl` fields in chat history.
- `src/lib/chat.server.ts` — normalizes a legacy HTTPS `imageUrl` into the standard image file part while preserving the original field.

Publish these website changes through the existing Lovable-connected workflow.

## Known release blockers

- The camera preview still needs a full-screen single-preview restructuring and portrait/landscape/front-camera device verification.
- Sky-blue/black/amber bubble styling is updated in the Main Chat renderer; workspace-specific message renderers still need a full consistency audit.
- Birthday sent-state is currently locally backfilled; server-persisted milestone state and comeback reminder edge-case tests remain.
- Billing doctor reports the canonical document presence and defaults, but invalid-status classification and the website's idempotent “Save current defaults” action remain.
- Server-authoritative heartbeat accounting still needs final-stop settlement and concurrency/retry tests.
- Generated image Storage upload and deletion are source changes only; they have not been tested against the live Firebase bucket or deployed Storage rules.
- No Android build, Worker test run, website build, deployment, or device test has been performed in this work.

## Google sign-in checklist (owner action; no rebuild for fingerprint registration)

1. In Google Play Console, open **Test and release → App integrity → App signing**. Copy both SHA-1 and SHA-256 fingerprints from **App signing key certificate**, and also both fingerprints from **Upload key certificate**.
2. In Firebase Console, open **Project settings → Your apps → com.bqsqwad.ai**. Add the Play app-signing SHA-1 and SHA-256, the upload-key SHA-1 and SHA-256, and the debug SHA-1 if debug builds are used.
3. In Google Cloud Console, open **APIs & Services → OAuth consent screen**. Confirm the app is **In production** if it should be available to all users; if it is **Testing**, only listed test users can authenticate. Confirm the Web OAuth client referenced by `default_web_client_id` exists.
4. Wait a few minutes, then sign out and retry Google sign-in from the Play-installed app.
5. Only replace `app/google-services.json` after owner approval. Do not change package ID, signing, or release identifiers as part of this fix.

The app now maps known Google configuration failures to a safe message and logs only a sanitized configuration code locally. **This does not prove the Firebase/Google OAuth configuration is fixed**; the owner must complete the console checks and test the Play-installed app.
