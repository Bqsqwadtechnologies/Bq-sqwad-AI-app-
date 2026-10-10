# BQ Sqwad AI — Approved Full Audit Findings and Repair Plan

Date: 2026-10-10
Branch: main
Scope: approved phases 1–11, with phase 6 included. Native Android Kotlin/Jetpack Compose and the existing Cloudflare Worker remain the product architecture.

## Verification and change-control rules

- Source inspection is not proof of runtime operation.
- Do not trigger a GitHub Actions workflow or build as part of this work.
- Preserve Firebase Authentication, required consent, server-side authorization and entitlements, rate limiting, AI request idempotency, and credit reserve/commit/reverse behavior.
- Keep provider secrets server-side in Cloudflare Worker secrets.
- The BQ Sqwad AI catalog is the customer-facing catalog. Google Play Billing is only for eligible checkout, purchase restore, and secure backend purchase verification.
- Do not change existing Google Play product IDs or base-plan IDs, invent prices, or claim an integration is live based only on a label/configuration record.
- Keep all changes on the existing main branch. Every code change should be independently reviewable and linked to its commit.
- Status labels: SOURCE PRESENT, PARTIAL, GAP CONFIRMED, or DEVICE/BACKEND VERIFICATION PENDING.

## Phase 1 — Full source audit and feature map

The repository contains `docs/architecture/MAIN-FEATURES-END-TO-END-AUDIT.md`, `docs/architecture/ADVANCED-AI-CAPABILITY-CATALOG.md`, and `docs/IMPLEMENTATION-STATUS-PHASE-01-42.md`. These are useful architecture records but explicitly do not prove every listed feature is end-to-end operational.

Repair plan:
1. Trace each major user-visible feature from Compose entry point through Android client, Worker route, authorization/entitlement, provider or integration, persistence, and UI result.
2. Label actual gaps separately from device/backend tests that cannot be performed without a build or deployed environment.
3. Keep this document and the feature audit synchronized as changes land.

## Phase 2 — Branding and logo consistency

Source reviewed: `app/src/main/java/com/bqsqwad/ai/ui/BrandingSettingsScreen.kt` and `BQSqwadApp.kt`. Branding settings uses the shared `BqLogo` component and `BqLogoBranding` store. Its current copy accurately limits the custom logo to shared in-app placements on the current device; it does not change the launcher icon or other devices.

Repair plan:
1. Search all Android source and resource references for alternate/hard-coded in-app logo paths and text fallbacks.
2. Route ordinary in-app logo placements through the shared component while preserving intentional launcher/adaptive icon assets.
3. Keep the existing visual design. Verify reset behavior, image selection failure behavior, crop/contrast, and reachability of branding settings by source inspection; device verification remains pending.

## Phase 3 — BQ catalog and billing separation

Source reviewed: `ProductCatalogScreen.kt`, `PlayBillingClient.kt`, `cloudflare-worker/src/plan-catalog-native.js`, `cloudflare-worker/src/google-play.js`, and `cloudflare-worker/src/index.js`.

Findings:
- The product screen has BQ-facing plan models and parsing for wrapped catalog responses, plan sections, features, bindings, entitlements, and contact-sales entries.
- Google Play product details are described in the UI as internal checkout mappings rather than a second customer-facing catalog.
- Client parsing includes filtering of credit-like plan entries and relies on server catalog data for visible plans.
- Source inspection alone does not establish that the deployed Firestore catalog, fallback catalog, Play mapping, localized prices, product eligibility, and entitlement records agree.

Repair plan:
1. Trace the canonical Worker catalog response and all client parsing/fallback paths.
2. Ensure Google Play responses never replace or become the displayed plan catalogue.
3. Use Play data only to resolve a BQ plan's configured product/base plan and launch an eligible purchase.
4. Ensure entitlement unlock happens only after server verification; keep pending purchases pending.
5. Preserve product/base-plan IDs, credit ledger semantics, current plan rules, Contact Sales treatment, and existing prices. Never synthesize a price when the canonical price is absent.
6. Add source-level checks for empty/malformed catalog responses and stale cache behavior.

## Phase 4 — Main chat and text experience

Source reviewed: `MarkdownText.kt`, `ChatAttachmentReader.kt`, `HistoryApiClient.kt`, and the main Compose app entry point.

Findings:
- Markdown renderer supports fenced code, headings, bullets, numbered lists, block quotes, inline bold/italic/code, and a copy-code action.
- Main chat source includes selection and sharing support added in prior commits.
- History client supports list, load, save, and delete requests, including a bounded message payload.
- Presence of helpers does not by itself prove all controls are reachable or that restored history appends correctly.

Repair plan:
1. Trace each visible chat action to a handler and its error/retry state.
2. Confirm selected history restoration, continuation ID handling, and server ownership checks.
3. Preserve selectable text, code copy, message copy/share, stop/cancel, and clear error feedback.
4. Do not add controls that are inert or claim an operation succeeded before the host/backend confirms it.

## Phase 5 — Research, files, and multimodal AI

Source reviewed: `ChatAttachmentReader.kt` and the existing architecture audit.

Findings:
- Attachment reader supports image MIME types and a defined list of text MIME/extensions.
- Per-file input is capped at 5 MiB; text is capped at 120,000 characters.
- PDF and Office text extraction are not implemented by this reader, and unsupported types are rejected.
- Source audit records one-image vision routing and rejects multiple images in the current path.
- The actual request pipeline must be traced before claiming that each accepted file type reaches the model intact.

Repair plan:
1. Trace picker → reader → composer state → request body → Worker/provider capability.
2. Ensure multi-file read failures are atomic and surfaced to the user rather than silently omitting an attachment.
3. Expand document support only where an available, maintainable parser and server contract exist; do not claim unsupported PDF/Office extraction.
4. Audit research source/citation display, source opening, saved research, and export independently.
5. Keep file limits and error messages explicit; do not persist temporary attachment content unnecessarily.

## Phase 6 — Camera, screen, and voice

Source reviewed through the current architecture audit and live-media source paths.

Findings:
- Camera preview/frame sampling and vision requests exist in source.
- Screen capture is designed around explicit Android MediaProjection consent and revocation callbacks.
- Voice uses Android speech recognition and text-to-speech paths; current documentation does not establish a low-latency full-duplex audio transport.
- Camera, screen and voice need permission/lifecycle/device testing; static source cannot prove the provider or hardware path succeeds.

Repair plan:
1. Verify deny/cancel/revoke behavior, session cleanup, repeated start/stop, background/foreground restrictions, and visible status.
2. Check that camera/screen frames and audio are not retained beyond the declared purpose.
3. Clearly distinguish speech-recognition + TTS from true real-time full-duplex voice.
4. Do not bypass Android permissions, foreground-service requirements, or platform eligibility rules.

## Phase 7 — Personal, Learning, Companion

The architecture audit identifies dedicated Personal Intelligence, Life Matters, Learning Studio, and Companion flows and Worker routes. It also lists persistence, progress/history, entitlement consistency, and plan gates as needing verification.

Repair plan:
1. Map each visible control to its actual route, server authorization, saved state, and displayed result.
2. Check empty/error/loading states and history/progress continuity.
3. Ensure memory is user-controlled and that inspect/delete behavior is connected to server state.
4. Treat unverified functionality as partial, not complete.

## Phase 8 — Business and Organization AI

The Worker imports native business/workspace, organization, integration, production, scheduler, training-asset, and YouTube modules. The existing audit notes that channel/destination metadata is not equivalent to authenticated external integration or a verified broadcast.

Repair plan:
1. Audit organization-scoped authorization and knowledge separation.
2. Trace training asset upload/finalization/download authorization.
3. Separate configured, connected, authenticated, and live states for each external connector.
4. Validate lead/inbox persistence, channel toggles, automation execution, and failure states.
5. Treat the YouTube streaming path as source-present but not device-verified; do not imply equivalent support for other destinations without their own adapters and end-to-end tests.

## Phase 9 — Advanced agents and automation

The advanced capability catalog describes orchestration, provider/model routing, multimodal routing, retries, memory, research, agent tools, evaluations, versioning, rollback, deployment states, and analytics. Those descriptions are requirements, not proof every item is wired.

Repair plan:
1. Match each advertised agent/workflow to an implemented route and executable handler.
2. Check tool allowlists, per-user/organization authorization, and consent before side effects.
3. Require confirmation for consequential actions, with explicit progress/failure reporting.
4. Keep retries idempotent and preserve credit reserve/commit/reversal.
5. Do not expose placeholder registry entries as working capabilities.

## Phase 10 — Admin, security, reliability

Source reviewed: `cloudflare-worker/src/index.js`, `PlayBillingClient.kt`, and the native Worker modules.

Findings:
- Worker source includes rate limiting, idempotency helpers, a configured super-admin check, and Firebase/Firestore integration.
- Client billing submits purchase information to the backend and handles pending/verifying states.
- Static source review does not establish complete route coverage, production secret configuration, or server behavior in a deployed environment.

Repair plan:
1. Audit protected routes for Firebase identity verification, required consent, role checks, capability/entitlement gates, and ownership checks.
2. Preserve rate limits, idempotency, credit reserve/commit/reversal, purchase verification, and admin audit records.
3. Ensure secrets remain Worker-side and logs avoid tokens, private user content, and provider credentials.
4. Make failure states actionable but avoid leaking internal stack traces or secrets.
5. Do not alter Firebase configuration or private keys as part of this source pass.

## Phase 11 — Verification plan and acceptance criteria

No build or workflow will be triggered as part of this approved pass.

Required verification is tracked in separate categories:
- Source: route/UI/reference tracing, static checks, and diff review.
- Build: pending until separately authorized.
- Backend: pending unless an existing safe, non-mutating endpoint check is available.
- Device: pending until an APK is built and exercised on hardware.
- Billing: pending until test purchases and server verification are exercised in an authorized test environment.

### Acceptance criteria
- No website/PWA/WebView replacement; native Android remains.
- No change to existing product or base-plan IDs.
- Customer-facing catalogue remains BQ Sqwad AI, not a Google Play catalogue.
- No locally granted subscription entitlement; server verification remains authoritative.
- No provider secrets in Android source.
- No weakening of authentication, consent, rate limiting, idempotency, or credit ledger behavior.
- Every code change linked to its commit and described with an honest verification status.
- Features are not called complete solely because UI, registry, or metadata exists.

## Initial status

This is a source-level findings and repair plan. It does not certify the current APK, deployed Worker, purchase flow, external integrations, or live media sessions. Detailed code changes and follow-up commit links are to be appended below as implementation proceeds.


## Implementation log

### 2026-10-10 — Billing offer safety and failure feedback

- Commit [627651d](https://github.com/Bqsqwadtechnologies/Bq-sqwad-AI-app-/commit/627651d706232ad16a85ae0c9f206a45db9b5405): `PlayBillingClient.kt` now fails closed when a specifically configured subscription offer cannot be found, instead of silently selecting another offer. This avoids launching checkout under different offer terms than the selected BQ plan.
- Commit [d25797b](https://github.com/Bqsqwadtechnologies/Bq-sqwad-AI-app-/commit/d25797b83885e91eea1f5eb87d09d9e4cb5ae6f5): purchase verification now surfaces a generic retryable failure state rather than leaving the UI stuck on “verifying” when the server request throws or required verification data is unavailable. The client still grants no entitlement itself; the backend remains authoritative.
- Verification performed: fetched the updated file from `main` and confirmed both code paths are present in source. No build, tests, workflow, deployed purchase, or device test was run. These changes therefore have source-inspection verification only.

### 2026-10-10 — Audit plan committed

- Commit [5702f30](https://github.com/Bqsqwadtechnologies/Bq-sqwad-AI-app-/commit/5702f3015a9b94b833dfc7286cb7efd09895081f): added this approved source-level findings, repair plan, acceptance criteria, and verification boundaries.
- Verification performed: file was fetched successfully from `main`. Documentation only; it does not establish runtime feature completion.


### 2026-10-10 — Catalog checkout messaging

- Commit [ba2fe22](https://github.com/Bqsqwadtechnologies/Bq-sqwad-AI-app-/commit/ba2fe2257772d1522528f0ef77389af9f20b7d2f): replaced customer-visible internal troubleshooting wording about Play product IDs/base plans with clear guest-vs-signed-in checkout guidance. This changes messaging only; it does not change product IDs, plan prices, eligibility, or purchase verification.
- Verification performed: fetched the edited source from `main` and checked that the new guest/signed-in branches are present. No build, workflow, or runtime checkout test was run.


### 2026-10-10 — Main Chat image attachment capability routing

- Commit [a9d5018](https://github.com/Bqsqwadtechnologies/Bq-sqwad-AI-app-/commit/a9d50188e51da613f246b5eb7c4f5d595bcfd65c): extended the Android vision request client to pass an explicit capability and optional system context; existing live-camera callers retain `live_camera` as the default.
- Commit [392267b](https://github.com/Bqsqwadtechnologies/Bq-sqwad-AI-app-/commit/392267bb1676210c5acfb204149ee206a3e8a9ab): Worker vision handling now resolves and charges the requested capability, defaulting to `live_camera`, and includes bounded caller-provided system context alongside the app knowledge prompt. Server capability resolution and credit reservation/commit/reversal remain in place.
- Commit [e9acaaa](https://github.com/Bqsqwadtechnologies/Bq-sqwad-AI-app-/commit/e9acaaaeb006c3ebea82e7041902366f06848fa5): Main Chat image attachments now forward the selected advanced capability and conversation system context instead of being treated unconditionally as live-camera usage.
- Verification performed: re-fetched and inspected the changed Android and Worker source and confirmed the intended call chain. No build, tests, workflow, deployed Worker request, or device test was run, in accordance with the approved verification boundary. Runtime and billing behavior remain pending verification.


### 2026-10-10 — Main Chat image and file rendering follow-up

- Commit [440af09](https://github.com/Bqsqwadtechnologies/Bq-sqwad-AI-app-/commit/440af0964034407a861eda0598333d5def3a9f08): user chat messages now render selected image attachments as image previews and other attachments as visible file cards; generated image responses use an in-chat image renderer rather than only printing the URL.
- Commit [651fab7](https://github.com/Bqsqwadtechnologies/Bq-sqwad-AI-app-/commit/651fab7337924a02299f0a5e537bfa854b778caf): saved chat history now retains attachment labels and MIME types so reloaded conversations can show file/image cards without persisting raw attachment bytes.
- Commits [e665b88](https://github.com/Bqsqwadtechnologies/Bq-sqwad-AI-app-/commit/e665b888a2880205c4e8f36f36f76b45997ab8c4) and [10b7d55](https://github.com/Bqsqwadtechnologies/Bq-sqwad-AI-app-/commit/10b7d553918f55953a2ac148182eb85527ca2969): Android vision requests now package multiple images in one bounded request.
- Commit [c891147](https://github.com/Bqsqwadtechnologies/Bq-sqwad-AI-app-/commit/c89114799e829a824e9fe0d07fe64a902c45e952): Main Chat accepts up to four image attachments and passes them to the vision client.
- Commit [2488ffa](https://github.com/Bqsqwadtechnologies/Bq-sqwad-AI-app-/commit/2488ffa542267595ebed2ecac37204ec28483fc6): OpenAI provider input supports multiple image parts.
- Commit [37c0264](https://github.com/Bqsqwadtechnologies/Bq-sqwad-AI-app-/commit/37c026471f69dc0e3f320819e40166276c6c3740): Worker validates image count, MIME shape, and aggregate encoded size, then sends all accepted images to the vision provider under the selected capability and credit reservation.
- Commit [f47be9a](https://github.com/Bqsqwadtechnologies/Bq-sqwad-AI-app-/commit/f47be9ae6729e35231ca313356f868ecd13d9cd4), followed by regex correction [8d69f03](https://github.com/Bqsqwadtechnologies/Bq-sqwad-AI-app-/commit/8d69f035173adfca8f2bfb1fb05576bc5af56045), adds rendering for standalone Markdown image links in assistant responses. Commit [638765f](https://github.com/Bqsqwadtechnologies/Bq-sqwad-AI-app-/commit/638765f4498ebf8bd2a11ec39dd53680beb6ef0e) shares the HTTPS image renderer between generated images and Markdown image blocks.
- Commit [f7e5e04](https://github.com/Bqsqwadtechnologies/Bq-sqwad-AI-app-/commit/f7e5e043f4b8cbf0e820651790887408ba2d8204), [046ad2e](https://github.com/Bqsqwadtechnologies/Bq-sqwad-AI-app-/commit/046ad2e56a6776fed72679c916a54303df1b9c6a), [44f4c05](https://github.com/Bqsqwadtechnologies/Bq-sqwad-AI-app-/commit/44f4c0525612541b8b71028a7ac87d290beea14a), and [20a4948](https://github.com/Bqsqwadtechnologies/Bq-sqwad-AI-app-/commit/20a494837941a9f2a38f0c1f28138b6d5f4dade9): preserve the prompt after request failures, persist generated-image URLs in saved history, and show a clear image-load failure state.
- Verification performed: fetched the edited files again and checked that the Android request, Worker image array, provider image parts, history fields, and rendering branches exist on `main`. No Android build, Worker test suite, workflow, deployed Worker call, billing test, or physical-device test has been run. Multiple-image request size remains capped at 8,000,000 encoded characters in total. Raw image bytes are intentionally not saved to chat history; restored history shows attachment cards, not the original image pixels.


- Commit [a3d50d8](https://github.com/Bqsqwadtechnologies/Bq-sqwad-AI-app-/commit/a3d50d890a518bbac68f47733f4203b9e280c2a2) updates the Worker history sanitizer to retain only bounded attachment metadata and HTTPS image URLs. This closes the server-side gap that otherwise discarded the new Android history fields.
- Commits [fedf7a3](https://github.com/Bqsqwadtechnologies/Bq-sqwad-AI-app-/commit/fedf7a35b816f9cd6ab16981ab09bba52bada0d0), [c0fa13d](https://github.com/Bqsqwadtechnologies/Bq-sqwad-AI-app-/commit/c0fa13de456dbd23d31ffe50162fe14830dca0b1), and [bc1b3e4](https://github.com/Bqsqwadtechnologies/Bq-sqwad-AI-app-/commit/bc1b3e43836e38f6c34e3437bc85c5d198869a98) add Gemini inline-image input, route vision requests through configured vision-capable providers (falling back to OpenAI when the selected capability is configured for a non-vision provider), and reject unsupported MIME types with bounded payload validation.
- Commit [e239454](https://github.com/Bqsqwadtechnologies/Bq-sqwad-AI-app-/commit/e2394547b182a1e988f5521d1cac436ecf41714f) adds user-facing recovery messages for oversized/unsupported image requests. Commit [77e0f82](https://github.com/Bqsqwadtechnologies/Bq-sqwad-AI-app-/commit/77e0f82799ed3c7aedc3552e063c33b749bc1d8b) moves local image decoding off the Compose UI thread.
- Commit [80293f0](https://github.com/Bqsqwadtechnologies/Bq-sqwad-AI-app-/commit/80293f0c7fa3650a30993ad1378ee42266ddc5e2) adds an Android unit test for parsing standalone Markdown image links. Commit [4470e4c](https://github.com/Bqsqwadtechnologies/Bq-sqwad-AI-app-/commit/4470e4c332f3e926b1fe63bf9f8889cefab9254d) adds Worker provider tests for multi-image OpenAI and Gemini request payloads.
- Verification performed: re-fetched the Android UI, history client, Markdown renderer, Worker route, and provider source after changes. The new tests are committed but have not been executed; no build/workflow/device/deployed-backend test has been run.


### 2026-10-10 — Generated image response contract correction

- Root cause found: the Worker image-generation handler returns `imageBase64`, while the Android `GenerationApiClient` previously read only `imageUrl`. As a result, the Main Chat image-generation path could receive a successful server response but have no usable image URL to render.
- Commits [8d719f2](https://github.com/Bqsqwadtechnologies/Bq-sqwad-AI-app-/commit/8d719f2636f78b7b3d66888e21f19ef1a19e74f0) and [36cf926](https://github.com/Bqsqwadtechnologies/Bq-sqwad-AI-app-/commit/36cf926cd9ac5bb30288dcdc8e740115000985fa) update the Android response model to read generated base64 image data and reject an empty image response.
- Commit [6918f4e](https://github.com/Bqsqwadtechnologies/Bq-sqwad-AI-app-/commit/6918f4eca5e77f2ef0244dbb9ccd68a878857a14) displays returned image bytes directly in the Main Chat assistant bubble and saves only the generated-image attachment metadata in history, not the raw image bytes.
- Verification performed: fetched the Worker generator and Android client/UI source, confirmed the response contract and rendering path are now aligned. No build or real image-generation request has been run; end-to-end confirmation is still pending.


- Commits [24341a6](https://github.com/Bqsqwadtechnologies/Bq-sqwad-AI-app-/commit/24341a6585c3a68460faaf317a45146be1089f38) and [d1dcf23](https://github.com/Bqsqwadtechnologies/Bq-sqwad-AI-app-/commit/d1dcf23d6d317b421a931eae7947fba8b35a60d4) isolate the generated-image response parser and add regression coverage for the Worker `imageBase64` response contract, including empty-response rejection. Tests remain unexecuted pending the authorized verification phase.


### 2026-10-10 — PDF attachments in Main Chat

- Commit [8ed0289](https://github.com/Bqsqwadtechnologies/Bq-sqwad-AI-app-/commit/8ed0289b980245623f8561b21bdf3f5d843a83d2): the attachment reader now accepts PDF files up to the existing 5 MiB per-file limit and encodes them for a provider-native document request. Text-based formats continue to be read as text, and images remain image attachments.
- Commit [837d7a2](https://github.com/Bqsqwadtechnologies/Bq-sqwad-AI-app-/commit/837d7a2375d54f01490d263475211c0d719b4d32): the Android multimodal client can send images, PDFs, or both through the authenticated vision/attachment route.
- Commit [b3d1a8d](https://github.com/Bqsqwadtechnologies/Bq-sqwad-AI-app-/commit/b3d1a8d8b9d205b69a0812527cc952982724a633) adds OpenAI `input_file` and Gemini inline document parts. Commit [062ed9d](https://github.com/Bqsqwadtechnologies/Bq-sqwad-AI-app-/commit/062ed9d91dc72b43b684a6881004a47c137bb933) validates PDF type, filename, per-kind count and combined encoded payload size on the Worker. Commit [f0037b2](https://github.com/Bqsqwadtechnologies/Bq-sqwad-AI-app-/commit/f0037b2ecdcfc9358ffd22a9c1b1be043bcfb89a) connects PDF attachments from the Main Chat picker through the provider request while showing the selected file in the chat bubble.
- Commit [37a68a2](https://github.com/Bqsqwadtechnologies/Bq-sqwad-AI-app-/commit/37a68a2334c79ed15dd3ab233d49a5377784dd0a) expands the provider payload tests to include PDF input, and [14c8623](https://github.com/Bqsqwadtechnologies/Bq-sqwad-AI-app-/commit/14c8623cc003e492facc79db44931536c84dc11c) tightens Android request typing for an optional image.
- Scope boundary: PDF is sent to a provider that accepts PDF/inline document input; the app does not perform local PDF text extraction. Office formats such as DOCX/XLSX/PPTX remain unsupported by the current attachment reader and must not be described as complete. The 8,000,000-character combined encoded payload cap still applies.
- Verification performed: source files and provider payload tests were fetched after changes; tests/build/runtime requests have not been run.
