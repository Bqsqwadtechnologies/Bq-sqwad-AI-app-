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
- 1.1 Composer two-row layout: DONE but not verified — `BQSqwadApp.kt` places mic/text/send on row one and attachments/model selector on row two.
- 1.2 No silent phone-recognizer fallback: DONE but not verified — server failure no longer auto-starts phone speech; explicit user action remains.
- 1.2 25-second timeout and Cancel during transcription: DONE but not verified — hard 25s cap in recorder and app path.
- 1.2 Recording bar with exact Cancel / Transcribe / Send and send-after-transcription: NOT DONE — voice-only bar rebuild still open.
- 1.3 One sealed Dictation state machine as the sole source of truth: NOT DONE — legacy independent Compose state flags remain.
- 1.4 Admin-configured transcription model: DONE but not verified — Worker dictation pricing / model selection path present.
- 1.4 Live `wrangler tail` reproduction: NOT DONE.
- 1.5 Android dictation state-machine tests and device layout QA: NOT DONE.

### Phase 2 — Teams (Prompt L)
- 2.1 Drawer opens Teams state before closing navigation: DONE but not verified.
- 2.2 Android Teams cached first paint: DONE but not verified — in-memory UID cache + background refresh; disk persistence / skeletons still open.
- 2.3 Android/Worker membership index and paged membership reads: DONE in source — `team-workspace-native.js` uses `teamMemberships` + paged `queryTeamPages` (limit 100, offset); contract test `team-membership-contract.test.js` added. Runtime NOT TESTED.
- 2.4 Teams v2 capacity: DONE in source — 200 active-member ceiling on join/approve in workspace + Teams v2; plan/admin seat policy and concurrency-safe reservation still open. NOT TESTED on live data.
- 2.5 Create, join, request, approve, invite link, leave parity across app and website: NOT DONE — full shared-data verification open.

### Phase 3 — Existing Android modules
- 3.1 Onboarding survey and profile/memory persistence: NOT DONE.
- 3.2 One-time White theme picker and pre-paint default: NOT DONE.
- 3.3 Presence heartbeat lifecycle: DONE in Worker route (`POST /presence/ping` mounted); Android client heartbeat wiring still NOT DONE.
- 3.4 MessagingStyle notification integration: NOT DONE.
- 3.5 Secret chat crypto UI integration: NOT DONE.
- 3.6 Business storage meter and expiry banner from live data: NOT DONE.
- 3.7 Composer credit/model gate and BQ-Codron lock sheet: PARTIAL — credit typing is not blocked while balance loads (`creditBalanceLoaded` starts true); BQ-Codron purchased-credit server gates exist; lock sheet UX still open.
- 3.8 Duplicate attachment component removal: NOT DONE.

### Phase 4 — Worker integration
- 4.1 Import and route all listed unused Worker modules: PARTIAL — orchestrator import and `/presence/ping` are mounted; remaining unused modules still open.
- 4.2 Orchestrator fallback, cost safeguards and idempotent settlement: PARTIAL in modules; live inactive claim remains until production tail confirms.
- 4.3 Vault: client access denied in `firestore.rules` / `storage.rules` for `vault/**` and `vaultIndex/**`; NOT DEPLOYED. Encrypted vault writes / conversation-path integration still NOT DONE.
- 4.4 Plan expiry enforcement, countdown and storage add-on policy: NOT DONE.
- 4.5 Server-side admin route enforcement and complete entitlements response: NOT DONE.
- 4.1 Worker `/play/verify` writes canonical `entitlements/{uid}/plans/{planId}`: DONE in source; unit-tested earlier.
- 4.1 Play RTDN verification and canonical revocation: NOT DONE. Legacy root entitlement aggregation from highest active plan: NOT DONE.
- 4.1 Android canonical plans: HTTP entitlement fetch + Firestore plans listener + **ON_RESUME refresh nonce** present in `BQSqwadApp.kt` (LifecycleObserver). DONE in source, NOT TESTED on device. Rules NOT DEPLOYED.
- 4.6 Worker `/ai` and `/stream` shared `canUseModel`, BQ-Codron purchased-credit rules, public catalog route: DONE in source; live request tests still pending.
- 4.6 Mixed-balance drain policy: DONE in billing-hold source; re-test on latest main pending.
- 4.6 Full-repository model-default audit: NOT DONE.

### Phase 5 — Android feature gaps
- 5.1 Three-dot chat menu and requested actions: NOT DONE.
- 5.2 Conversation export formats: NOT DONE.
- 5.3 Generated-file cards and full-screen viewer: NOT DONE.
- 5.4 Secret chat folder, biometric unlock, auto-lock and ciphertext-only sync: NOT DONE.
- 5.5 Monthly feedback and feature-news delivery: NOT DONE.
- 5.6 Task lifecycle events and reconnect-safe live work timer: NOT DONE.
- 5.7 Business screens backed by live storage/expiry/entitlement data: NOT DONE.

### Phase 6 — Verification
- 6.1 Prior signed APK/AAB for Android source `e2075473` remains historical; current main may differ. Do not ship those binaries as the final release without a new signed build.
- 6.1 Worker tests: contract tests added for membership index + presence route; full `npm test` expected via workflow on this commit.
- 6.2 Device/emulator QA: NOT DONE.
- No production Worker deployment or Play Store publication is claimed.

### Continuation checkpoint — 2026-11-02 (this agent)
Continued from Phase 0/1 implementation checkpoint on real `main` only (no workspace copies, no duplicate feature trees).

Verified already on main before new work:
- `POST /presence/ping` mounted in `cloudflare-worker/src/index.js`
- Orchestrator import present
- Team membership index + paged reads in `team-workspace-native.js`
- Teams v2 200-member ceiling
- Android ON_RESUME entitlement refresh nonce + Firestore plans listener
- Composer not blocked on live credit load (`creditBalanceLoaded` defaults true)

Added on this continuation:
- `cloudflare-worker/test/team-membership-contract.test.js`
- `cloudflare-worker/test/presence-contract.test.js`
- Audit refresh (this file)

Still open for the next continuation (priority order):
1. Dictation sealed state machine + voice-only recording bar (Phase 1.2–1.3)
2. Android presence heartbeat client (Phase 3.3)
3. Play RTDN + highest-active-plan legacy entitlement aggregation (Phase 4.1)
4. Vault encrypted write path + rules deploy (Phase 4.3)
5. Teams create/join/approve parity verification app ↔ website (Phase 2.5)
6. Device QA + new signed APK/AAB after source stabilizes (Phase 6)
