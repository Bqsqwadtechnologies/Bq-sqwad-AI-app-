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
