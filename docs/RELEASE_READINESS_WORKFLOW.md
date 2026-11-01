# BQ Sqwad AI release-readiness workflow

The manual **BQ Full System Verification** workflow is the pre-release gate for the Android app, Cloudflare Worker, and website.

## Run it

1. Open the app repository: https://github.com/Bqsqwadtechnologies/Bq-sqwad-AI-app-
2. Open **Actions**.
3. Select **BQ Full System Verification**.
4. Select **Run workflow** on `main`.
5. Monitor the Android, Worker, website, source-contract, and production-reachability steps. Fix any failed step and rerun the workflow.

## Required repository access

The workflow checks out the private website repository. Add an Actions repository secret named `BQ_WEBSITE_REPO_TOKEN` in the app repository. It must be a fine-grained token with **Contents: Read-only** access to `Bqsqwadtechnologies/bqsqwadai-b3064406`.

## Checks performed

- Android Java 17 / Gradle unit tests and debug APK build.
- Worker JavaScript syntax checks, automated Node tests, and package checks.
- Website Vitest suite, lint, billing regression test, and production build.
- Cross-platform credit/billing contract checks.
- Live AI provider-orchestrator wiring and priced Gemini fallback source checks.
- Business Studio checks that its plan expiry comes from the authenticated Business subscription and storage values come from the server-owned business storage record; missing records display as unavailable, never fabricated numbers.
- Google Play checkout source regression checks and production Worker reachability.

The workflow does not deploy the Worker, publish the website, upload an AAB to Play, or publish a store release. The separate **Signed Release APK and AAB** workflow builds signed release artifacts after signing secrets are configured.

## Current Android version

- Version code: `15`
- Version name: `1.0.15`

The version code was advanced from 14 to 15. Do not increment it again unless preparing the next distinct release.
