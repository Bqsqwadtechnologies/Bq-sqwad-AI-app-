# COMPLETION_AUDIT

## Phase 1 DictationState mapping — apply status

A verified 18-hunk patch exists in the agent workspace and applies cleanly to current `main` `BQSqwadApp.kt`.

The `docs/phase1-dictation-state.patch` file on GitHub may still be a few dozen bytes short of the verified local patch (apply can fail with "corrupt patch at line 241").

**Do not commit a partial migration.**

### How to finish Phase 1 shell mapping

Option A — agent provides complete patch in chat (ask for "paste full clean patch").

Option B — local machine with the verified patch file:

```bash
git fetch origin && git checkout main && git pull
# Place the verified 15784-byte patch as docs/phase1-dictation-state.patch
git apply --check docs/phase1-dictation-state.patch
git apply docs/phase1-dictation-state.patch
git add app/src/main/java/com/bqsqwad/ai/ui/BQSqwadApp.kt
git commit -m "feat(android): Phase 1 map BQSqwadApp dictation onto DictationState"
git push origin main
```

After apply, confirm: `rg "var dictationState by remember" app/src/main/java/com/bqsqwad/ai/ui/BQSqwadApp.kt`
And no: `rg "dictationRecording = true" app/src/main/java/com/bqsqwad/ai/ui/BQSqwadApp.kt`

## Phase 0 — DONE in source (not live-verified)
## Phase 1 helpers / Teams / theme System default — DONE
## Phase 1 BQSqwadApp DictationState wiring — WAITING on clean full patch apply
## Phase 2 coverage — NOT fully complete
## Phase 3–5 — prior main work remains
## Phase 7 — builds/tests/QA NOT RUN

## Theme
- Default `system` (white surfaces when device is light). ThemeOncePicker recommends System.


## PDF Exact-Code Fix Prompt — Phase 1 Android white theme

- **DONE — not build-verified:** `ui/theme/Theme.kt` defaults to `light`; unknown/empty theme values resolve to White; explicit `system` and `ocean` retain system-dark behavior; status and navigation bars follow the active palette.
- **DONE — not build-verified:** `res/values/styles.xml` uses a light launch parent and white window/status/navigation backgrounds to prevent the dark launch flash.
- **DONE — not build-verified:** `MainActivity.onCreate` performs the one-time `white_default_v1` migration before Compose starts, respecting `bq_device_prefs.theme_chosen`.
- **DONE — not build-verified:** `BQSqwadApp.kt` uses Light as the settings default, marks explicit settings/onboarding theme choices, and places `light` first in the Appearance modes while retaining System and Dark.
- **DONE — not build-verified:** `OnboardingContract.kt` and `OnboardingSurveyScreen.kt` default to `light`.
- **DONE — reference scan only:** removed `ui/chat/ThemeOncePicker.kt`; repository code search found its helper/function names only in that file.
- **NOT RUN:** Android Gradle build, unit tests, emulator first-launch/status-bar checks. Owner to run these locally.
- **NOT CHANGED:** optional Phase 1.7 `bubbleAi` color adjustment; no visual evidence was available to justify changing call sites.


## Verification update — Android Phase 1

- GitHub Actions run [41492274141](https://github.com/Bqsqwadtechnologies/Bq-sqwad-AI-app-/actions/runs/41492274141) executed `:app:testDebugUnitTest :app:assembleDebug` against commit `fd94b737b3e666f72bb780dd43c0be6576b57267`.
- **FAILED at Kotlin compilation**; this is not a passing build. Compiler output includes: `DictationState.kt:23` needsCredits hides supertype member; `BQSqwadApp.kt:3651,3671,3672,3702` unresolved `ChatMessage.attachmentNames` (and dependent `put`); `BQSqwadApp.kt:4754,4772,4790,4808` missing exhaustive `TASKS` branches; `MyTasksWorkspace.kt:10` inaccessible RowColumnParentData.weight.
- These failures prevent claiming Phase 1 build verification. The workflow stopped before the remaining checks.


## PDF Exact-Code Fix Prompt — Android compile repair

- **DONE — source repaired, build pending:** `core/DictationState.kt` renames the `Failed` constructor flag to `requiresCredits`, preserving the base `needsCredits` computed property without a JVM/Kotlin member clash.
- **DONE — source repaired, build pending:** `ui/BQSqwadApp.kt` derives `ChatMessage.attachmentNames` from `attachments`, restoring TXT/JSON/CSV export references without duplicating stored attachment state.
- **DONE — source repaired, build pending:** `ui/BQSqwadApp.kt` adds explicit `Workspace.TASKS` branches to `featureLongDescription`, `featureHowItWorks`, `featurePermissions`, and `featurePrivacyHeading`.
- **DONE — source repaired, build pending:** `ui/MyTasksWorkspace.kt` removes the inaccessible `androidx.compose.foundation.layout.weight` import; `weight` resolves from the enclosing `RowScope`.
- **NOT VERIFIED:** Android compilation/tests are awaiting the existing GitHub Actions run for the repair commits.

## PDF Phase 3 — Firebase rules ownership and storage alignment

- **DONE — source aligned:** APP `firestore.rules` remains the sole deployed Firestore rules source. WEB `firebase.json` continues to deploy only `storage.rules`; no Firestore deploy target was added.
- **DONE — source aligned:** WEB `firestore.rules` is explicitly marked NOT DEPLOYED from the website repo and identifies APP `firestore.rules` as owner.
- **DONE — source aligned:** APP and WEB `storage.rules` now both include explicit deny rules for `/vault/**` and `/vaultIndex/**`; the final catch-all deny remains last in WEB rules.
- **NOT VERIFIED:** Firebase rules deployment/simulator checks have not yet been completed.

## PDF Phase 4 — Worker unwired modules

- **DONE — source:** `presence-route.js` is redundant because `/presence/ping` is implemented inline in `src/index.js` using `writePresence` from `presence-usage-native.js`; the redundant module is removed.
- **DONE — source:** `composio-tools-native.js` is redundant and its placeholder execution path is not used; `src/index.js` already implements authenticated Composio sessions, permission checks and execution. The duplicate module is removed.
- **NOT WIRED:** `business-tool-catalog.js` has no call site in the Worker request path; kept in place and not imported speculatively, as required by the prompt.
- **NOT VERIFIED:** Worker `npm test` is pending; run not available from the repository's existing workflow configuration.

## PDF Phase 5 — Live task timer events

- **DONE — source implemented, build pending:** Worker `/ai/stream` emits `taskStarted`, real `phase` updates for streaming and usage settlement, and `taskEnded` on success, failure and request abort; abort cancels the upstream reader and reverses the reservation.
- **DONE — source implemented, build pending:** Android `ChatApiClient` parses named SSE events; Main Chat stores the server `startedAt`, updates elapsed time once per second without resetting on recomposition, and displays `label · mm:ss`.
- **NOT VERIFIED:** Android/Worker automated checks are pending in GitHub Actions.

## PDF Phase 6 — Single-token pricing

- **DONE — source implemented, build pending:** Website shared helpers `creditsPerToken`, `cachedInputCreditsPerToken`, and `outputCreditsPerToken` normalize canonical per-token customer rates and legacy per-1k Firestore records.
- **DONE — source implemented, build pending:** Admin pricing writes canonical per-token fields; the server validates all three rates, preserves legacy input compatibility, and normalizes records. Website chat preflight and settlement consume the normalized values.
- **DONE — source implemented, build pending:** Worker `token-billing-native.js` reads canonical per-token customer rates first and converts legacy per-1k fields only as fallback. Provider USD per-token/per-million values remain internal cost telemetry.
- **DONE — test sources updated:** Both billing vector JSON files now express token prices per single token; vector and legacy-compatibility test cases were updated.
- **NOT VERIFIED:** Website tests/build and Worker tests await CI access; no website build has run because the shared verification workflow's website token receives HTTP 403.
