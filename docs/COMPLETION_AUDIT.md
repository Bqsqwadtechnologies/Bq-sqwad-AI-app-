# COMPLETION_AUDIT

## Complete on main this cycle
- Business AI playbook client + screen
- Worker playbook contract tests (pass)
- Prior G/H/I foundation (entitlements, models, onboarding APIs, etc.)

## Environmental
- Full `./gradlew testDebugUnitTest assembleDebug` needs Android SDK in CI
- Composer five-edit wiring still optional polish inside restored `BQSqwadApp.kt` (components present)

## Prompt L / Prompt M completion audit — 2026-11-02

### Phase 1 — Android + Worker (Prompt L)
- 1.1 Composer two-row layout: DONE but not verified — `app/src/main/java/com/bqsqwad/ai/ui/BQSqwadApp.kt` now places mic/text/send on row one and attachments/model selector on row two; text supports six lines. Exact 80dp sizing, rounded container fit and 360dp/tablet QA remain unverified.
- 1.2 No silent phone-recognizer fallback: DONE but not verified — server failure no longer invokes `startSpeechRecognition`; explicit `Use phone speech` action is shown in the error controls (`BQSqwadApp.kt` lines 2878–2895, 3960–3985).
- 1.2 25-second timeout and Cancel during transcription: DONE but not verified — `withTimeout(25_000L)`, session checks, remembered stop callback, and job cancellation (`BQSqwadApp.kt` lines 2872–2930).
- 1.2 Recording bar with exact Cancel / Transcribe / Send and send-after-transcription: NOT DONE — the Android composer has not yet been rebuilt into the requested voice-only bar.
- 1.3 One sealed Dictation state machine as the sole source of truth: NOT DONE — legacy independent Compose state flags remain in `BQSqwadApp.kt` around lines 131420–131900.
- 1.4 Admin-configured transcription model: DONE but not verified — Worker reads `adminAdvancedOptions/dictation_pricing.config.transcriptionModel`, validates supported IDs and uses the selected model before Whisper fallback (`cloudflare-worker/src/index.js` lines 2421–2495).
- 1.4 Live `wrangler tail` reproduction and confirmation of the live project key/model/price: NOT DONE — no live transcription request or tail trace was available in this run.
- 1.5 Android dictation state-machine tests and device layout QA: NOT DONE.

### Phase 2 — Teams (Prompt L)
- 2.1 Drawer opens Teams state before closing navigation: DONE but not verified — `BQSqwadApp.kt` line 1473.
- 2.2 Android Teams cached first paint: DONE but not verified — `app/src/main/java/com/bqsqwad/ai/ui/teams/TeamsHubScreen.kt` now initializes from a UID-keyed in-memory cache and refreshes in the background with a visible Retry action. Disk persistence, skeleton placeholders and a bounded network timeout remain NOT DONE.
- 2.3 Android/Worker membership index and paged membership reads: NOT DONE — `cloudflare-worker/src/team-workspace-native.js` still scans `teamWorkspaces` with `limit: 50` at line 42.
- 2.4 Seat limits sourced from plans/admin and support for 200+ members: NOT DONE — prior fixed query limits remain in `team-workspace-native.js` lines 42 and 66; end-to-end seat policy was not changed.
- 2.5 Create, join, request, approve, invite link, leave parity across app and website: NOT DONE — full route and shared-data verification was not completed.

### Phase 3 — Existing Android modules
- 3.1 Onboarding survey and profile/memory persistence: NOT DONE.
- 3.2 One-time White theme picker and pre-paint default: NOT DONE.
- 3.3 Presence heartbeat lifecycle: NOT DONE.
- 3.4 MessagingStyle notification integration: NOT DONE.
- 3.5 Secret chat crypto UI integration: NOT DONE.
- 3.6 Business storage meter and expiry banner from live data: NOT DONE.
- 3.7 Composer credit/model gate and BQ-Codron lock sheet: NOT DONE.
- 3.8 Duplicate attachment component removal: NOT DONE.

### Phase 4 — Worker integration
- 4.1 Import and route all listed unused Worker modules: NOT DONE.
- 4.2 Orchestrator fallback, cost safeguards and idempotent settlement: NOT DONE — existing cross-platform contract records fallback as inactive.
- 4.3 Encrypted vault writes, deny-client rules and permission test: NOT DONE.
- 4.4 Plan expiry enforcement, countdown and storage add-on policy: NOT DONE.
- 4.5 Server-side admin route enforcement and complete entitlements response: NOT DONE.
- 4.1 Worker /play/verify now writes `entitlements/{uid}/plans/{planId}` with `source: google_play`, module, active/grace state, expiry and included entitlement codes; existing verified subscription-cycle migrations also backfill the canonical plan. `cloudflare-worker/src/google-play.js` — DONE and unit-tested in run `41406800730`.
- 4.1 Play RTDN notification endpoint/verification and canonical revocation on refund, expiry, hold or revoke: NOT DONE. Legacy root entitlement is still updated from the latest purchase, not derived from the highest active canonical plan.
- 4.1 Android now refreshes canonical entitlements on launch/workspace refresh and ON_RESUME, and listens to `entitlements/{uid}/plans` changes; `app/src/main/java/com/bqsqwad/ai/ui/BQSqwadApp.kt` and `EntitlementsClient.kt` — DONE but not verified by device tests. The Firestore owner-read rule was added in `firestore.rules`, but it is NOT DEPLOYED; the existing rules workflow deploys Storage rules only.
- 4.6 Worker /ai and /stream call shared canUseModel before provider execution, reject inactive/mismatched models, require purchased/subscription credits for BQ-Codron, and disable provider substitution for BQ-Codron; cloudflare-worker/src/index.js and cloudflare-worker/src/ai-model-catalog-route.js — DONE but not verified by live request tests.
- 4.6 Mixed-balance policy is explicit and tested: BQ-7-Star drains daily free → subscription → permanent; BQ-Codron drains subscription → permanent and never daily free. `cloudflare-worker/src/billing-hold.js` and `cloudflare-worker/test/billing-hold.test.js` — Worker syntax/unit-test job passed in run `41406554515` for commit `9f4bfa28`; the later no-fallback adjustment is pending a fresh run.
- 4.6 Worker public model catalog route is now mounted at GET /models/catalog and its availability projection includes daily free credit for BQ-7-Star while keeping that allowance out of BQ-Codron eligibility; cloudflare-worker/src/index.js and cloudflare-worker/src/ai-model-catalog-route.js — DONE but not verified.
- 4.6 Full-repository model-default audit: NOT DONE — remaining provider/model routes and workspace agents have not yet been exhaustively checked.

### Phase 5 — Android feature gaps
- 5.1 Three-dot chat menu and requested actions: NOT DONE.
- 5.2 Conversation export formats: NOT DONE.
- 5.3 Generated-file cards and full-screen viewer: NOT DONE.
- 5.4 Secret chat folder, biometric unlock, auto-lock and ciphertext-only sync: NOT DONE.
- 5.5 Monthly feedback and feature-news delivery: NOT DONE.
- 5.6 Task lifecycle events and reconnect-safe live work timer: NOT DONE.
- 5.7 Business screens backed by live storage/expiry/entitlement data: NOT DONE.

### Phase 6 — Verification
- 6.1 Android signed release APK/AAB workflow: DONE and verified for Android source commit `e2075473` — run `41405450536`; `assembleRelease` and `bundleRelease` succeeded, APK v2 signature verification passed, AAB JAR signature verification passed, and both artifacts were uploaded. APK artifact SHA-256: `635954c49addcde72b92af5b69fa3a019cd3ee0100df8d779ab18fe4cb168c6f`; AAB artifact SHA-256: `9d5f1beefa745042e0227f4e9dc311a90ffed6a52c186c66fa6b7d3ed3694776`. These binaries predate the latest Worker-only model eligibility changes; Android Kotlin source has not changed since `e2075473`.
- 6.1 Android package identity audit: DONE and verified for Android source commit `e2075473` — run `41405450413`; Gradle unit tests and generated APK application ID check passed.
- 6.1 Full system verification: run `41405450555` succeeded for commit `e2075473`; it does not verify later Worker-only changes or website build/tests. Latest full-system verification for current main is pending/in progress (runs `41406245431` and `41406444015`).
- 6.1 Worker syntax and unit tests: DONE and verified for the latest tested Worker source commit `8712594b` — run `41406800730`; this includes the Google Play canonical-entitlement renewal test and bucket-drain tests. Production deployment job was SKIPPED. Latest full-system verification run `41406800676` is queued. Local shell commands were not run because no local repository checkout/runtime was used.
- 6.2 Device/emulator QA (360dp/tablet composer, voice controls, teams, onboarding, theme, notifications, presence, secret chat): NOT DONE.

Status note: This audit deliberately distinguishes source edits from runtime verification. No production Worker deployment, Play Store publication, or device QA is claimed.