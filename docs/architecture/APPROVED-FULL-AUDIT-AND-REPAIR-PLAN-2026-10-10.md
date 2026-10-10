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
