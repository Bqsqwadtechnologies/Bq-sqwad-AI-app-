# Confirmed Fix Pass — 2026-09-26

## Deployment verification
- Confirmed the Firebase deployment workflow exists for project `bqsqwadai` and has both `push` (Firebase-related paths) and `workflow_dispatch`.
- Added an explicit preflight that reports whether `FIREBASE_SERVICE_ACCOUNT_JSON` or the legacy `FIREBASE_TOKEN` is actually present in the Actions secret context.
- The first post-fix deployment attempt reached the backend test gate, but the service-account secret was empty in the runner environment, so no Firebase deployment or `/health` verification occurred.
- Fixed the two concrete backend test failures observed in that run: core capability count now matches the 15-entry catalog, Business plan test matches the current Play-eligible catalog, and tiered yearly base-plan IDs such as `plus-yearly` are accepted without changing any IDs.

## CI/release
- `release-apk-aab.yml` now triggers on pushes to `main` and still supports manual `workflow_dispatch`.
- Release builds record the Git commit SHA in `BUILD-INFO.txt`.
- Android `BuildConfig.BUILD_SHA` exposes the short SHA inside the app.
- Release CI uses concurrency cancellation so superseded automatic builds do not unnecessarily consume Actions capacity.
- `deploy-functions.yml` keeps its existing automatic Firebase-path push trigger and manual trigger.

## Main Chat
- Replaced the top-right AccountCircle icon with the Material Settings gear.
- Main Chat wallpaper now uses the existing dark gradients in dark themes and MaterialTheme-derived light gradients in light themes.
- The build SHA is visible in Main Chat and the Settings About card.

## Package/identity
- Package/application ID remains exactly `com.bqsqwad.ai`.
- No Product IDs, base-plan IDs, offer IDs, Firebase IDs, route IDs, or other commercial identifiers were changed.

## Guest access
- Preserved the existing product behavior: signed-out guests may browse the native shell/workspaces that are otherwise available to them; authentication is required when protected actions such as sending chat are attempted.
- `MainShell.openWorkspace` was not changed to force sign-in for every workspace because the current AuthGate/product design explicitly supports guest browsing. Protected workspace entitlements continue to be checked server-authoritatively.

## Verification limits
- GitHub Actions verification is available through the workflow runs.
- A physical-device clean uninstall/install, live consent tap, and visual light/dark switch require an actual Android device/session; they are not honestly claimable from repository inspection alone.
