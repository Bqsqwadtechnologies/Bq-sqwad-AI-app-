# BQ Sqwad AI — Approved Audit Findings & Repair Plan

Date: 2026-10-10
Target: existing `main` branch only
Repository: `Bqsqwadtechnologies/Bq-sqwad-AI-app-`
Application: native Android Kotlin + Jetpack Compose (`com.bqsqwad.ai`)

## Verification standard

This is a source-level audit, not a claim of device-tested completion. A capability is end-to-end verified only after its native entry point, permission/lifecycle behavior, authenticated API contract, server-side authorization and entitlement checks, provider/connector execution, persistence/output, and failure/revocation paths have been exercised. No build or GitHub Actions workflow is authorized by this plan; neither will be triggered.

## Current source findings

### Phase 1 — Full feature map
The repository contains native chat, search/research, camera, screen capture, voice, companion, learning, personal/life workspaces, business/organization administration, catalog/billing, and advanced capability contracts. Existing documentation explicitly says source presence is not proof of operational completeness. The older end-to-end audit marks several capabilities partial or not end-to-end verified. Direct inspection of the current source also found newer code than some audit statements describe: Main Chat now prepares and submits image/text attachments and saves successful conversations; a separate History screen/client lists, opens, and deletes chats; Personal Intelligence, Life Matters, Companion, and Learning each have dedicated composables. The feature map must distinguish implemented source paths from backend-integrated, entitlement-gated, credential-dependent, and device-tested behavior. Older audit documents must be corrected where they contradict current source.

Evidence: `docs/architecture/MAIN-FEATURES-END-TO-END-AUDIT.md`; `docs/architecture/ADVANCED-AI-CAPABILITY-CATALOG.md`.

### Phase 2 — Branding and logo consistency
`BrandingSettingsScreen.kt` exists and exposes a single selected image for shared `BqLogo` placements, with reset-to-bundled-logo behavior. Recent commits normalized fallback sizing and clarified the single-source setting. This establishes a shared branding path, but does not prove every feature screen uses it or that the screen is reachable from normal settings. Launcher assets are intentionally separate. Repair work should inventory all in-app logo renderers and replace only true logo bypasses, preserve existing screen design, and verify the central setting is navigable and updates shared placements.

Evidence: `app/src/main/java/com/bqsqwad/ai/ui/BrandingSettingsScreen.kt`; commits `5fe3d2fad2f2a0a267e4032f9d880572eb1442f5`, `f26ddcd09a63d0038f1ef9fb0afb881ed0e06934`.

### Phase 3 — Canonical BQ catalog vs Google Play
`ProductCatalogScreen.kt` requests the Worker’s native catalog, parses canonical BQ plan data, caches the last valid response, and displays a diagnostic rather than inventing sample plans when no visible plans are returned. The Worker has a canonical fallback commercial catalog in `cloudflare-worker/src/plan-catalog-native.js`; comments specify Firestore admin configuration can override it. The client also maintains Play product details for checkout. Remaining audit work must trace every UI string and billing call to ensure Google Play product discovery is only an internal checkout/price-resolution step, never the user-facing catalog source; validate canonical plans, plan periods, feature bindings, contact-sales rules, and entitlement gates without changing product/base-plan IDs or fabricating prices.

Evidence: `app/src/main/java/com/bqsqwad/ai/ui/ProductCatalogScreen.kt`; `app/src/main/java/com/bqsqwad/ai/ui/AdminPlanCatalogScreen.kt`; `cloudflare-worker/src/plan-catalog-native.js`; `docs/architecture/PHASE-21-COMPLETE-PRICING-CATALOGUE-MIGRATION.md`.

### Phase 4 — Main chat and text experience
`MainActivity.kt` accepts Android ACTION_SEND and ACTION_PROCESS_TEXT input and forwards it into the Compose app. `MarkdownText.kt` renders headings, inline emphasis, code blocks, lists, quotes, and dividers; code blocks have a copy action. Prior source changes added selection support and a catalog-independent text formatting path. Current source now reads multiple attachments (5 MB per file) and submits extracted text with the prompt; one image can be submitted through the existing /ai/vision route, while more than one image is rejected. The supported reader handles images and text-like formats only; PDF and Office documents are not extracted, and large text content is truncated at 120,000 characters. Main chat saves successful non-private conversations via HistoryApiClient, and HistoryScreen supports listing, opening, and deleting saved conversations. The current implementation now also offers Continue in Chat and restores the saved messages plus history ID. Repair should target unsupported file formats, clear partial-failure behavior, attachment-size/format UX, history error states, and verify continuation rather than reimplement existing paths. Preserve streaming/cancel and authenticated credit semantics.

Evidence: `app/src/main/java/com/bqsqwad/ai/MainActivity.kt`; `app/src/main/java/com/bqsqwad/ai/ui/MarkdownText.kt`; `docs/architecture/MAIN-FEATURES-END-TO-END-AUDIT.md`.

### Phase 5 — Research, files, multimodal
Web search and Deep Research routes are described as present in source, but the existing audit only marks them as source-implemented pending runtime verification. File analysis, image/video generation, multi-file reasoning, upload progress, secure temporary storage, citations/source inspection, and export must be checked against actual API contracts rather than the capability directory alone. Do not expose provider credentials in the APK. Do not mark features operational merely because their names exist in a registry.

Evidence: `docs/architecture/MAIN-FEATURES-END-TO-END-AUDIT.md`; `docs/architecture/ADVANCED-AI-CAPABILITY-CATALOG.md`; `app/src/main/java/com/bqsqwad/ai/core/WorkspaceApiClient.kt`.

### Phase 6 — Camera, screen, voice
The existing audit describes a native Camera2 preview/frame-sampling path to `/ai/vision`, a MediaProjection screen viewer with OS consent and revocation callback, and a speech-recognition/AI/TextToSpeech voice loop. It explicitly says these are not real-device verified and that voice is not a proven low-latency full-duplex transport. Repair should focus on lifecycle cleanup, permission denial/revocation, repeated start/stop, visible foreground-service state, interruption, stale-frame handling, and error recovery. Do not claim real-time full-duplex or platform broadcast support without a complete transport/encoder path.

Evidence: `docs/architecture/MAIN-FEATURES-END-TO-END-AUDIT.md`; `app/src/main/java/com/bqsqwad/ai/core/WorkspaceApiClient.kt` (API client contracts).

### Phase 7 — Personal, learning, companion
Current source routes Personal Intelligence to PersonalIntelligenceWorkspace, Life Matters to LifeMattersWorkspace, Companion to CompanionWorkspace, and Learning to CompleteLearningStudioWorkspace; these are dedicated UI flows, not merely a generic AiTaskWorkspace. Learning source loads catalog and progress and submits learning sessions; Companion source has personality/mode, memory and plan gates. Remaining work is to verify every control is wired to a real API and persisted state, avoid local counters being mistaken for server progress, ensure plan gates match server entitlements, and test memory enable/disable and history. Runtime/device verification remains outstanding.

Evidence: `docs/architecture/MAIN-FEATURES-END-TO-END-AUDIT.md`; `docs/architecture/ADVANCED-AI-CAPABILITY-CATALOG.md`.

### Phase 8 — Business and Organization AI
Business workspace, knowledge, leads/inbox, training asset upload/finalization, analytics, and organization production metadata have source paths. Source inspection found a YouTube-specific path that obtains an OAuth-linked account, creates a live broadcast/stream, retrieves the RTMP ingestion endpoint, and starts the native camera streaming foreground service using StreamPack. This path is not device/platform verified. Other destination records still do not prove equivalent integrations, and scene/overlay, health/reconnect, and non-YouTube transport coverage need separate evidence. Repair should distinguish saved configuration from connected/authorized accounts and show accurate connection, permission, health, and error state.

Evidence: `docs/architecture/MAIN-FEATURES-END-TO-END-AUDIT.md`; `cloudflare-worker/src/index.js`.

### Phase 9 — Advanced agents and automation
The advanced capability catalog describes orchestration, tool selection, retries, provider failover, memory, research, learning, business workflows, agents, evaluations, versioning, rollback, and scheduled tasks. These are requirements, not proof that every capability is deployed. Repair should inventory each client-to-Worker route, enforce per-action permissions and server-side capability/entitlement checks, use human confirmation for consequential actions, and expose job state/failure only where a real backend job exists.

Evidence: `docs/architecture/ADVANCED-AI-CAPABILITY-CATALOG.md`; Worker native modules referenced by `cloudflare-worker/src/index.js`.

### Phase 10 — Admin, security, reliability
Worker source includes Firebase identity verification, server-side capability controls, per-user rate limiting, idempotency helpers, and admin identity logic. These safeguards must be preserved. Audit should verify authorization at every sensitive route, tenant/organization data isolation, safe handling of errors and logs, credit reserve/commit/reversal behavior, admin audit trails, provider kill switches, and absence of secrets from Android resources. No security gate should be weakened to make a screen appear functional.

Evidence: `cloudflare-worker/src/index.js`; `cloudflare-worker/src/plan-catalog-native.js`; `docs/architecture/ADVANCED-AI-CAPABILITY-CATALOG.md`.

### Phase 11 — Verification plan
No Android build, APK, live-device test, Worker deployment, or GitHub Actions workflow was run as part of this source inspection. Verification must be reported separately as: source inspected, static checks available but not run, build not run by instruction, and real-device/backend checks pending. The acceptance matrix will cover authentication and consent, chat streaming/stop/retry, file ingestion, history persistence, canonical catalog and purchase verification, entitlements/credits, logo changes, camera/screen/voice permissions and lifecycle, workspace data isolation, admin controls, and external integration/broadcast honesty.

## Repair order

1. **Baseline & map (Phase 1):** verify current `main` HEAD; trace Android entry points, API client methods, Worker routes, provider adapters, Firestore persistence, capability/entitlement gates, and user-facing status for every approved feature. Update this report as evidence changes.
2. **Branding (Phase 2):** inventory logo calls; route all in-app brand marks through the shared logo component and make the existing central setting discoverable. Do not change launcher icon or screen design.
3. **Catalog (Phase 3):** trace catalog fetch/cache/parse/render and Play checkout mapping; fix only demonstrated violations. Canonical BQ catalog is the display source; Play is checkout/verification only.
4. **Chat/text (Phase 4):** complete attachment content delivery and history lifecycle if supported by existing APIs; verify select/copy/code copy/share/process-text and streaming recovery.
5. **Research/files (Phase 5):** close upload, multimodal, citation and export gaps against implemented Worker routes; otherwise show an honest unavailable state.
6. **Camera/screen/voice (Phase 6):** review lifecycle and failure paths in source; write device test steps, but do not claim them passed without device evidence.
7. **Personal/learning/companion (Phase 7):** trace the dedicated workspace UI to each API and persisted field; fix mismatched plan gates or local-only progress indicators; ensure memory is user-controlled.
8. **Business/organization (Phase 8):** align connection/broadcast status with actual connector/encoder support and verify organization access isolation.
9. **Agents/automation (Phase 9):** inventory real job/tool routes; preserve permission gates, confirmations, retries and audit trails.
10. **Admin/security (Phase 10):** validate fail-closed behavior, role checks, idempotency, rate limits, credit semantics and redacted diagnostics; no secret exposure.
11. **Verification matrix (Phase 11):** publish exact checks and label each item source-reviewed, static-checked, build-tested, backend-tested, or device-tested. No workflow/build dispatch without separate explicit authorization.

## Non-negotiable constraints

- Work only on existing `main`; no replacement app, web wrapper, or new repository.
- Native Android Kotlin + Jetpack Compose remains the product.
- Preserve Firebase authentication, consent, history, backend, billing, credits, feature gates and admin boundaries.
- Keep provider secrets server-side.
- Canonical BQ Sqwad AI catalog drives visible plan names, features, prices and availability. Google Play Billing is used only for checkout, localized eligible price resolution, and secure purchase verification. Do not change existing product/base-plan IDs, invent prices, or add Paystack/NGN conversion.
- Preserve Worker identity verification, consent enforcement, super-admin checks, rate limiting, idempotency, and credit charge/commit/reversal semantics.
- Do not run a build, dispatch a workflow, deploy the Worker, or claim runtime/device success as part of this work unless separately authorized.


## Implementation log — 2026-10-10

The following source changes have been committed to `main` after the approved audit. These are code/documentation commits only; no build, workflow, or deployment was triggered.

- `85edc4a` — initial approved source findings and repair plan.
- `ebce7bc` — corrected the report to recognize current attachment/history and dedicated-workspace implementations.
- `63f90e3` — refreshed the existing end-to-end audit to reflect current source.
- `0eb0fe5` — prevent partial multi-file attachment submissions when any selected file fails to prepare.
- `9e719d3` — reject text attachments over 120,000 characters instead of silently truncating them.
- `2c87d0f` and `022344f` — add a Continue in Chat action and restore selected history messages plus their history ID into Main Chat.
- `8e20a43` — replace the misleading “Connected capability” label with “Requested capability” and clarify that listing a feature does not establish an external connection.
- `efb007c` — remove the unused legacy PlansSheet that listed Google Play products directly, leaving the canonical BQ Sqwad AI catalog as the app's plan-browsing screen.
- `84304d3` — document saved-chat continuation and remaining attachment limitations.
- `05c3315` and `825d83f` — correct Organization Studio findings: a YouTube OAuth/create-live/RTMP camera-stream path exists in source, while the device-to-YouTube flow and other destinations remain unverified or partial.
- `3242f04` — align the plan with the saved-chat continuation implementation.

### Verification status for these changes

- Source edits were committed successfully to `main`.
- Read-after-write inspection confirmed the attachment guard, explicit text limit, history continuation callback/state restoration, and capability disclosure are present in the current source.
- Static code review only; no Gradle build or test suite was run.
- No APK was produced, no GitHub Actions workflow was dispatched, and no Worker was deployed.
- Android runtime, purchase verification, provider execution, permission lifecycle, and live broadcast checks remain pending. Do not mark any of them passed without test evidence.


---

## Progress log — 2026-10-12

### Phase 1 — Full source audit and feature map
- Completed a current-state source audit and feature table in `docs/architecture/PHASE-01-REPOSITORY-AUDIT.md`.
- Explicitly separated source-wired features from partial functionality and runtime verification still pending.
- Recorded the app's Worker API configuration, legacy Functions boundary, known document-extraction gaps, camera/screen/voice limitations, external-channel limitations, and unverified provider/purchase/device requirements.
- Phase 1 source deliverable: **COMPLETE**. This is not an end-to-end runtime PASS.

### Phase 2 — Branding and logo consistency
- Source inventory across the native UI found shared logo rendering through `BqLogo` in `BqDesignSystem.kt`; the branding settings screen uses the same component. No alternate direct in-app logo drawable renderer was found in the inspected feature screens. The launcher asset remains separate by design.
- Confirmed branding settings is reachable through the existing app settings callback; no new screen or navigation path was added.
- Fixed a confirmed persistence issue in `BqLogoBranding.set()`: it previously swallowed persisted-URI permission errors and could still report the logo as saved. It now validates the selected image and requires persistent read access before saving.
- Reduced custom-logo memory pressure by downsampling images to the rendered logo size in the shared `BqLogo` component. The bundled logo, circular crop, sizing, app theme, and image-generation loader were not changed by this fix.
- Commit: `06f304825737d8794997a0133b6450ff1382a2a9`.
- Phase 2 remains **IN PROGRESS** until the changed Kotlin source is build-checked and logo choose/reset/relaunch behavior is exercised. No build or GitHub Actions workflow was run or triggered.


### Phase 2 continuation — 2026-10-12
- Expanded the source inventory across the native UI Kotlin files. Confirmed the central `Brand & App Logo` entry is exposed from the existing Settings screen and navigates to `BrandingSettingsScreen`; did not add a duplicate settings route.
- Found one remaining in-app logo bypass in `BqGeneratingIndicator.kt`: the animated AI-generation indicator loaded `R.drawable.bq_sqwad_logo` directly, so it ignored the user's selected logo.
- Replaced that direct drawable rendering with `BqLogo(54.dp, Modifier.alpha(alpha))`. The animation remains, while the displayed brand now follows the same persisted custom-logo setting as other shared logo placements.
- Commit: `676609a5a94769be0f8919b9c10961c5b833808c`.
- Launcher assets remain separate by design. No changes were made to the app's logo asset, authentication, billing, backend, or app navigation beyond the existing branding-settings route.
- Verification: read-after-write source review only. Gradle build, unit tests, APK generation, device testing, Worker deployment, and GitHub Actions dispatch were not run.
- Phase 2 remains **IN PROGRESS** pending build and runtime verification of choose, reset, and persistence after app restart.


### Testing coverage repair and Phase 3 start — 2026-10-12
- Added the Android instrumentation runner and AndroidX test dependencies to the existing native app Gradle configuration.
- Added `MainActivityInstrumentedTest.kt` with launch smoke coverage for normal app launch, Android `ACTION_SEND` text sharing, and `ACTION_PROCESS_TEXT` selected-text handoff. These tests are intended to catch entry-point crashes; they do not claim to exercise all UI flows, backend integrations, billing, or camera/microphone hardware.
- Added a connection guard to `PlayBillingClient.start()` and a public catalog-refresh path. A Play product query attempted before Firebase sign-in could previously return without loading product details; refreshing the catalog now retries the Play mapping query while the billing screen is open, so sign-in need not require leaving and reopening the screen.
- Connected the existing catalog refresh/load action to that Play mapping refresh. The BQ-native catalog remains the visible source for plan names, descriptions, features, and canonical price/availability; Google Play product details remain limited to checkout and localized store-price resolution.
- Phase 3 source review confirms the native catalog parser accepts current and cached/wrapped payloads, preserves the last valid catalog when a live response is empty, and does not invent sample plans. Existing Worker tests cover public catalog visibility, shared credit packs, fallback catalog IDs, and canonical Play mapping. Further review still needs to trace base-plan/offer selection, contact-sales restrictions, credit/entitlement outcomes, and failure/retry behavior.
- Commits: `58c817ad96b11c123795227e463efab0c8e4f022` (instrumentation runner/dependencies); `017a09ddce2089ddcc3fc12128a7d1e4601c3fc` (instrumentation smoke tests); `0b68a9ef98a1195b34bed4b58355b6bd3dfa4062` and `e2a67c1ded4cad2716834bc7223d335a0f1834f2` (Play mapping refresh fix).
- No Gradle build, unit/instrumentation test run, APK generation, GitHub Actions dispatch, real purchase, or Worker deployment has been performed. Phase 2 is still pending execution of the new instrumentation tests plus logo selection/reset/persistence checks. Phase 3 is **IN PROGRESS**, not complete.


- Follow-up: updated `.github/workflows/validate-main.yml` to run `:app:connectedDebugAndroidTest` on a managed API 35 emulator after the debug build/unit-test step. The workflow remains `workflow_dispatch` only; this commit did not dispatch it. Commit: `3d1a74d017b74b75b53e092363e6d0d14c4db9c5`.


### Phase 4 start — Main Chat and text experience — 2026-10-12
- Started Phase 4 against the existing native Compose Main Chat. Kept the existing streaming/cancel path, BQ AI Features launcher, shared-text handoff, attachment reader, markdown/code rendering, feedback actions, image actions, and saved-history APIs; did not replace the chat screen or alter the Worker API architecture.
- Fixed a privacy boundary bug: turns composed while **Private Chat** is enabled could be included in a later history save after Private Chat was turned off. New turns are now tagged by message ID while private, and every history snapshot path (normal text/file chat, image generation, image regeneration, image editing, and resizing) filters those private turns out. Restoring a saved chat clears the in-memory private-message tracking for the restored conversation.
- Corrected the stale attachment failure message so it lists the formats the current reader supports (PDF, DOCX, XLSX, PPTX, and supported text documents) instead of incorrectly claiming only PDF/text files are supported.
- Commits: `6d6f1225fabc3dee76d16db2a4467dbaa32db06d` (private-turn filtering for main chat); `68a5b2af4ae5c9cb07e12f0da9ef10d38d03c75c` (private-turn filtering for image-history updates); `5e1a6971bca25c2ab6ff80b29dfc31ce7fc8b02e` (attachment error copy).
- Source review confirmed all four ordinary history snapshot sites and the separate image-regeneration snapshot exclude IDs recorded during Private Chat. Runtime privacy/history behavior is not yet tested.
- No Gradle build, unit/instrumentation test run, APK generation, GitHub Actions dispatch, purchase, or Worker deployment was performed. Workflow remains manual-only. Phase 4 is **IN PROGRESS**; continue reviewing history loading/deletion errors, message action correctness, cancellation/retry behavior, attachment handoff, and UI/keyboard behavior before moving to Phase 5.
