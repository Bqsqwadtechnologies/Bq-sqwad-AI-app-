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

## Verified from repository Actions and Cloudflare deployment metadata (2026-10-28)
- Worker workflow run **#27** for commit `c1cfe4edaf24e445492df6a7bb09142592bb5d85` completed successfully; the syntax/unit-test job and production deploy job both passed.
- Cloudflare's deployment list reports the latest `bq-sqwad-ai-app` deployment at `2026-10-28T17:30:08Z`, with 100% traffic assigned to the new version. Expected `BQ_BUILD_ID` from the workflow is `c1cfe4edaf24`.
- Worker workflow run **#28** for commit `04ec743ca06db292324759a4d39759a51b1a9923` passed the post-deploy health smoke test, which asserted `status=ok` and the expected build ID `04ec743ca06d`. Run #29 (test-only follow-up commit) is queued/running separately.
- An authenticated live-chat billing smoke test was not executed because no test-account credential is configured/available to this session. Do not treat token-formula settlement as verified.
- A Firebase rules workflow file is present, but no corresponding run was returned by the Actions run listing during this session. Therefore Storage rules deployment and the `FIREBASE_SERVICE_ACCOUNT` secret remain unverified.
