# BQ Sqwad AI deployment checklist

## Worker deployment
- Workflow: GitHub Actions → **Deploy Cloudflare Worker (manual)**.
- Changes under `cloudflare-worker/**` on `main` now run tests and then deploy automatically; manual dispatch remains available and requires `confirm=DEPLOY`.
- Required GitHub repository or production-environment secrets: `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID`.
- Worker runtime secrets to verify in Cloudflare (never commit values): `BQ_FIREBASE_SERVICE_ACCOUNT_JSON`, provider credentials required by enabled routes, and `COMPOSIO_API_KEY` (or supported Composio user-key configuration). Actual provider names depend on the active provider configuration.
- Current workflow dry-run/deploy passes `BQ_BUILD_ID`, but this checklist does not establish that the live health endpoint exposes it or that a production smoke test has passed.

## Firebase rules deployment
- Workflow: GitHub Actions → **Deploy Firebase security rules**.
- Required GitHub secret: `FIREBASE_SERVICE_ACCOUNT` containing the service-account JSON for project `bqsqwadai`. The workflow checks for it and never prints the secret.
- Workflow deploys `storage.rules` and `firestore.rules` on relevant pushes to `main`, or when manually dispatched.
- Verify the Storage bucket `bqsqwadai.firebasestorage.app` exists and Storage is enabled in Firebase Console → Storage. Confirm bucket location and App Check enforcement in Firebase Console; these settings cannot be verified from repository source alone.
- After deploy, test authenticated owner upload/read/delete for `users/{uid}/uploads/**`, `users/{uid}/generated-files/**`, and `avatars/{uid}/**`; verify cross-user reads/writes are denied.
- The workflow cannot prove that GitHub secrets are configured until an Actions run executes. Do not report a successful deploy until the run is green.

## Production smoke tests still required
1. Worker health endpoint reports the expected build ID.
2. Authenticated `hello` chat succeeds and its settled credits match the shared token-pricing formula, not a flat one-credit charge.
3. Run `cd cloudflare-worker && npm ci && npm test`.
4. Run Android `./gradlew testDebugUnitTest assembleDebug`.
5. Run website `npm ci && npm test && npm run build`.
6. Compare the same prompt and account on Android and web and verify identical settled credit cost.
7. Verify generation and uploads app → website → app, and verify stored media paths survive chat-history reload.
