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
Business workspace, knowledge, leads/inbox, training asset upload/finalization, analytics, and organization production metadata have source paths. Existing audit states named external channels are not proof of authenticated adapters, and Organization Studio destinations do not establish an encoder/output pipeline. Repair should distinguish saved configuration from connected/authorized accounts; show accurate status, permission boundaries, health/error state, and avoid claiming actual streaming until capture, encoding, transport, destination auth, reconnect, and compliance are implemented.

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
