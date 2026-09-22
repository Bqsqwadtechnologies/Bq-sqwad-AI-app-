# BQ Sqwad AI — Backend and Website Changes

## Scope
This repository contains the native Android app and its existing Firebase Functions backend. The native client never receives provider secrets.

## Google Play Billing
- Google Play Billing Library 9.1.0 is the current stable release verified against Android Developers documentation.
- Native purchases are sent to `/play/verify` with the Play purchase token.
- Entitlements and credit grants must be created only after server-side verification.
- `purchaseToken` is the idempotency key.
- The backend must verify subscriptions with `purchases.subscriptionsv2.get` and one-time products with `purchases.products.get`.
- Acknowledge non-consumable/subscription purchases only after verification and entitlement delivery.
- Consume configured consumable products only after verified delivery.
- Required secret: a Google Play service-account credential must exist in backend secret/environment storage. It must never be committed to GitHub or shipped in the APK.
- Required package: `com.bqsqwad.ai`.
- Required Play Console linkage: Google Play Developer API access for the service account and the BQ Sqwad AI application.

## Runtime Play catalogue
Firestore document: `playCatalog/config`
Each product entry must be explicitly configured by the administrator:
`planId`, `productId`, `basePlanId` (subscriptions), `productType` (`subs` or `inapp`), `enabled`, `consumable`, optional `creditGrant`, and entitlement mapping.

No product IDs, prices, or credit amounts are invented by Android.

## Entitlements
Use the existing central entitlement/credit model. A verified purchase must update the shared entitlement record and/or credit ledger atomically. Existing legacy entitlements remain untouched.

## RTDN
Configure Google Play Real-time Developer Notifications through Pub/Sub. The backend must process duplicate notifications idempotently and update renewal, cancellation, grace period, hold, pause, expiry, refund and revocation states.

## Business / Organization
Business AI and Organization AI remain in the native app. Native app screens do not show their plan prices. Business/Organization payment remains website-managed where applicable. The website-payment steering switch must remain OFF until explicitly configured and reviewed for applicable Google Play policy/program rules.

## Paystack
Paystack remains available to the website/backend for legacy subscribers and website Business/Organization payments. It must not be used as an in-app Android purchase flow.

## Required external setup
1. Create/configure Play subscription and one-time products in Play Console.
2. Approve stable product IDs/base-plan IDs.
3. Link the Play developer account to the Payments profile.
4. Grant the backend service account the required Play Developer API permissions.
5. Configure RTDN Pub/Sub.
6. Configure license testers and internal test track.
7. Populate `playCatalog/config` with approved product mappings.
8. Verify the backend against Play before enabling paid native purchases.

## Important
Until the approved Play catalogue and backend service-account configuration exist, the Android client must not claim a purchase is successful or grant entitlement locally.


## Implementation notes added in the native migration pass
- Verified Play purchases are keyed by a SHA-256 digest of the purchase token in `playPurchases`; the raw token is still stored only in the protected backend record for provider verification.
- The canonical entitlement document is `entitlements/{uid}`, matching the existing capability authorization path. This prevents a verified Play purchase from being written to a plan-suffixed document that the runtime would not read.
- A Pub/Sub RTDN handler `googlePlayRtdn` is exported from the existing Functions entry point. Configure the Google Play RTDN topic to `google-play-rtdn` in the same project/region before enabling it.
- RTDN events are idempotent by event ID and re-read subscription state from Google before updating the canonical entitlement.
- Native Paystack checkout/deep-link handling has been removed from the Android client. Existing backend Paystack functions remain for website/legacy use.
- The native app only displays Google Play localized prices obtained from Play product details; no native NGN price source is used by the purchase UI.


## Live Camera / Live Voice / Streaming phase

Implemented on the native Android app without changing the release workflow or creating a second project:

- Added a native Camera2 preview surface with front/back lens switching and periodic JPEG frame capture.
- Added authenticated backend camera-frame analysis through the existing provider gateway; image bytes and provider secrets stay server-side at the provider boundary.
- Added a visible Android foreground-service notification for active camera and microphone sessions, with Android 14+ camera/microphone foreground-service types already declared in the manifest.
- Upgraded the main chat client to authenticated SSE response streaming with a native Stop generating action that cancels the client connection.
- Added a live voice workspace using Android speech recognition partial results, AI replies, Android text-to-speech, and a visible foreground-session notification.
- Existing Firebase authentication and credit authorization remain the gate for AI requests.
- No provider key, token, product price, or Play product ID was added to the APK.

Not yet verified in this environment: signed APK/AAB build, physical-device camera/microphone QA, Play policy review, and production end-to-end streaming/vision latency. The release workflow was not modified.


## Native product catalog UI integration — 2026-09-21
- Added a professional native Products & Plans surface at `ProductCatalogScreen.kt`.
- The screen reads active plan metadata from the existing authenticated `/plans` endpoint and approved Google Play mappings from `/play/catalog`; it does not create a second pricing or entitlement system.
- Google Play `ProductDetails` remains the authoritative customer-facing price. The Android UI does not hard-code catalog prices or convert currencies.
- The screen groups Business AI, AI Companion, Learning AI, TruthGuard AI, AI Life Mathis, and Credits/Add-ons and provides loading, empty, retry/error, and cached offline states.
- Purchase actions are disabled when Google Play has no confirmed product price and report that access is granted only after backend verification.
- Google Play base-plan selection is now explicit when a catalog entry supplies a base-plan ID; this prevents the UI from accidentally launching a different billing period.
- Existing Paystack code remains backend/website/legacy-only. Native Android does not expose Paystack checkout.

## Required backend/config follow-up for the master prompt
- The native app now consumes the existing `playCatalog/config` and `plans` endpoints. The next catalog migration must extend the canonical configuration used by the website/admin rather than introducing another store.
- The authoritative product schema still needs to expose feature registry records, plan-feature bindings, quotas, availability, add-on eligibility, credit/rate-card metadata, plan versions, effective dates, and change notices to the native app. Do not invent those values in Android.
- The admin side must remain the writer of pricing/feature configuration; Android remains read-only for commercial configuration.
- Business AI in the native app is intended to use Google Play Billing for eligible digital plans/add-ons under the master prompt. Any legacy website-payment wording in older documentation should not be copied into the Android UI.


## Canonical catalog integration — continued
- Added authenticated `GET /native/catalog` as the native read-only catalog contract.
- The endpoint reads existing Firestore configuration without writing it and returns sanitized plan metadata, approved Play mappings, feature-registry records, plan-feature bindings, enabled add-ons, and whether `config/businessPricing` exists.
- Native product UI now reads the canonical catalog rather than maintaining a second hard-coded product grouping.
- Added authenticated `GET /entitlement` so the purchase surface can display backend-authoritative access state.
- Removed the obsolete native `NativeFeatureCatalog.kt`, which contained hard-coded commercial plan prices and feature/catalog data that conflicted with the master prompt's runtime-configuration requirement.
- The native catalog remains read-only: Android does not edit prices, feature bindings, quotas, add-ons, or Play mappings.

## Continued native completion — September 21, 2026
- Native product catalog now resolves feature states per plan-feature binding and displays live limits and coming-soon entries.
- Authenticated `/credits` and `/usage` endpoints expose the backend credit balance and append-only usage ledger to the native client.
- Native WorkspaceApiClient now reads the credit balance and usage history from those canonical endpoints.
- Admin configuration writes now create append-only `adminConfigAudit` records with configuration version, actor, previous state, and submitted change.
- Privileged admin APIs now require the configured super-admin email plus an existing server-side Firebase admin/super-admin role claim; client-side UI visibility is not treated as authorization.
- Google Play purchase verification rejects a Play purchase owned by another account and prevents a new Play purchase from duplicating an active legacy entitlement for the same plan.
- No GitHub Actions workflow was triggered during this work.


## Complete commercial catalog migration — September 22, 2026

- Replaced the code-defined commercial fallback with a single canonical catalog in `functions/plan-catalog.js`, version 3.
- All commercial plan/product IDs in the canonical catalog use lowercase letters, numbers and hyphens only; underscore IDs are rejected by validation and by the admin save endpoint.
- The catalog now carries product type, recurring/one-time behavior, billing periods, base-plan IDs, offer IDs, USD list prices, proposed-price status, Play eligibility by billing period, 3-day trial eligibility, 7-day grace period, Google-controlled account hold, resubscribe behavior, plan-change behavior, country availability, limits, features, entitlements, quantity tiers, add-on quantity rules, Contact Sales state and active state.
- Added the admin-only `GET /admin/plan-catalog` read contract backed by `planCatalog/config` with code-catalog fallback.
- Added the admin-only `POST /admin/plan-catalog` configuration contract. Writes are versioned and audited in `adminConfigAudit`. Server-side validation rejects invalid/duplicate commercial IDs, product IDs containing underscores, and invalid tier IDs.
- Native `/play/catalog` and `/native/catalog` now fall back to the canonical Play projection when Firestore Play configuration has not yet been populated. Firestore configuration remains authoritative once present.
- Google Play projection uses one subscription product ID with multiple base plans where configured; yearly Business Professional, Business, and Business Enterprise are Contact Sales in the catalog projection, while proposed TruthGuard and Life Mathis yearly prices are not silently published to Play.
- Native Play purchase selection now respects the configured base plan and available configured trial offer. The 3-day offer is not applied to add-ons or exam add-ons.
- Verified Play entitlements now retain product ID, plan ID, base-plan ID, offer ID, tier ID, quantity, purchase token/reference, source, subscription state, start/end information and active state. A per-purchase `playEntitlements` record is also stored while the existing canonical `entitlements/{uid}` compatibility record remains intact.
- Legacy Paystack backend functionality remains untouched and is not used by native Android checkout.
- No GitHub Actions workflow was modified or triggered by this catalog migration.


## End-to-end verification pass — September 22, 2026

- Google Play Billing remains intentionally deferred; no Play product creation or purchase testing was performed in this pass.
- Fixed backend SSE/system-prompt newline serialization so the existing Functions source emits valid JavaScript string literals and SSE frame separators.
- Removed the obsolete native Paystack callback/deep-link handling from MainActivity; legacy Paystack backend/website support remains untouched.
- Removed client-side super-admin email gating from the workspace navigation path; admin authorization remains server-side through the existing protected admin APIs.
- Fixed the native Contact Sales flow so a canonical commercial plan ID is preserved when a catalog product opens Contact Sales; server-side plan-name resolution remains the fallback.
- Fixed an invalid Kotlin source escape in the workspace routing branch.
- Attachment selection and persistent chat history are still identified as incomplete end-to-end areas and are not being marked complete until their full client→backend→storage/history flows are implemented and verified.
- No GitHub Actions workflow was triggered by this pass.


## End-to-end integration audit — September 22, 2026

- Verified the native purchase path: catalog fetch → Play ProductDetails query → base-plan/offer selection → Google Play Billing flow → server verification → Firestore entitlement → service restoration.
- Native verification now sends base-plan and offer context and the server independently verifies the subscription line item returned by Google Play.
- Restored purchases for both subscriptions and one-time products are routed through the same server verification path.
- Play subscription acknowledgement is performed only after successful server verification/entitlement persistence.
- RTDN updates subscription state and restores/deactivates services according to the verified Play state.
- Fixed a malformed duplicated rate-limit helper in `functions/capability-runtime.js` that could prevent backend syntax validation.
- Expanded the backend syntax-check script to include `google-play.js`, `play-catalog.js`, and `plan-catalog.js`.
- No workflow was triggered during this audit. A live Android build and live Google Play purchase test still require the existing manual workflow/test environment and Play Console configuration.


## 2026-09-22 — Main feature Phase 1 and Phase 2 completion work

### Phase 1 — Main Chat
- Connected the main-chat file picker to real local attachment processing instead of a placeholder message.
- Supported attachment pipeline now reads user-selected images and text-based documents through Android's document provider and sends the actual content to the existing authenticated AI backend.
- Image attachments use the existing authenticated vision endpoint; text attachments are included in the authenticated chat request.
- Unsupported binary document types are rejected explicitly instead of being falsely presented as attached.
- Added authenticated Firestore-backed chat history endpoints:
  - GET /history
  - GET /history/:id
  - POST /history
  - DELETE /history/:id
- History records are scoped to the verified Firebase uid on the server.
- Main chat automatically saves completed conversations after an AI response.
- Added native Chat History UI for listing, opening, and deleting saved conversations.
- No billing/catalog changes were made.

### Phase 2 — Authentication and Consent
- Added a server-verified consent requirement for protected API access.
- Firebase ID tokens continue to be verified server-side before user identity is used.
- Required consent is stored in the existing userConsents collection and checked before protected API routes are processed.
- Added a native consent gate that observes Firebase authentication state, loads the server consent record, and lets the user save required Terms/Privacy acceptance plus optional training consent.
- Existing local consent preferences remain only a convenience cache; the backend is the authority for protected access.
- No provider/API secrets were added to the Android app.
- No billing/catalog changes were made.

### Runtime verification boundary
- Source-to-backend wiring has been implemented and reviewed, but this environment did not execute a physical-device/emulator build or live Firebase request. A successful source change must not be described as a real-device runtime pass until that device test is performed.


## 2026-09-22 — Main feature Phase 3 and Phase 4 implementation

### Phase 3 — Web Search / Research
- Android Web Search now presents returned source URLs as actionable browser links.
- Search requests continue through the authenticated Firebase to backend /web-search path.
- Normal Search and Deep Research remain controlled by the server capability gate and configurable rules.
- Tavily credentials remain server-side.
- No billing/catalog changes were made.

### Phase 4 — Live Camera and Screen Viewer
- Added a native foreground camera capture session using Camera2/ImageReader.
- Live camera frames are captured by the foreground service rather than depending on the Compose screen remaining visible.
- The foreground service performs sampled vision requests through the existing authenticated /ai/vision backend.
- Added a visible foreground notification with an explicit Stop action.
- Camera permission is requested before the camera foreground service is started.
- Camera lens selection and live AI prompt can be updated while the service is active.
- Screen capture is now owned by the foreground service after Android MediaProjection consent.
- Screen viewer frames continue to the authenticated vision endpoint while the user leaves the app.
- Android MediaProjection stop/revocation is handled through MediaProjection.Callback.onStop(), which stops the session and foreground service.
- Screen and camera status/results are mirrored to app-local live-session state so the UI can reconnect to an active foreground session.
- The UI no longer automatically stops a live camera/screen session merely because the user navigates away from the workspace.
- No provider secrets are stored in the app.
- No billing/catalog changes were made.

### Runtime verification boundary
- Android source paths and service lifecycle were implemented against the current foreground-service and MediaProjection requirements. A physical-device/emulator run was not available in this environment, so real permission-dialog, camera hardware, screen-off, OEM battery-management, Android-version, and Firebase live-request tests remain device verification tasks rather than claims of runtime success.


## Phase 5 — native realtime voice
- Replaced the previous SpeechRecognizer → text chat → Android TTS chain with a native realtime speech-to-speech session.
- Added `RealtimeVoiceSession.kt` using a WebSocket transport and PCM microphone/speaker streams.
- Server creates an ephemeral OpenAI Realtime client secret; the OpenAI API key remains server-side.
- Server-side VAD is enabled and a `speech_started` event immediately cancels/flushes current output for barge-in.
- Live Voice remains owned by `LiveMediaForegroundService`, so leaving the screen does not dispose the realtime session.
- Voice UI no longer displays conversation transcripts or AI reply text.
- Added admin-controlled `live_voice_pricing` configuration. Native code reads pricing/config from the backend; no voice price is hardcoded in Android.
- Added admin GET/POST controls at `/admin/live-voice-pricing` and the authenticated `/realtime/session` endpoint.
- Added OkHttp only because the native realtime WebSocket transport requires it.
- No workflow was triggered.

## Phase 6 — Personal / Life Matters / Learning Studio
- Workspace requests now use distinct backend capabilities: `personal_intelligence`, `life_matters`, and `learning_studio`.
- Personal Intelligence loads server-backed memory and exposes the memory enable/disable control.
- Workspace execution remains behind server capability/entitlement/credit controls.
- No prices were hardcoded into these workspace screens; commercial access remains backend/admin controlled.


## Phase 7/8 — social integrations and admin control
- Added a server-side social integration registry for Facebook Pages, Instagram, YouTube, TikTok, and X.
- Admin-configurable OAuth client IDs, scopes, callback URL, enable/disable state, and global test mode are stored in the backend configuration.
- Client secrets are encrypted through the existing server-side secret store; they are never stored in Android source code.
- Added admin endpoints for social configuration, secret storage, connection status, OAuth start/callback, and test mode.
- Connected account tokens are stored server-side and are not returned by account-list endpoints.
- Added OAuth state expiry and X PKCE handling.
- Test mode reports configuration/readiness without creating an external social post.
- Verified super-admin capability access bypasses normal entitlement/credit gating, so the designated admin can exercise paid capabilities for testing without purchasing each product.
- The external platforms still control their own OAuth approvals, scopes, audits, quotas and publishing restrictions. For example, TikTok requires the relevant Content Posting scope and user authorization, and unaudited clients can be restricted to private posts; YouTube upload also requires OAuth authorization and may be restricted for unverified projects. citeturn0search0turn0search1turn0search4turn0search16
- No GitHub Actions workflow was triggered.


## Phase 9 — Central Provider Credentials & Live Configuration
- Added centralized server-side provider secret lifecycle.
- Admin Control Center now exposes API & Provider Secrets for Gemini, OpenAI, Tavily, legacy Paystack credentials, social credentials, and arbitrary future provider IDs.
- Secrets are encrypted with AES-256-GCM using the BQ_SECRET_ENCRYPTION_KEY runtime secret; plaintext secrets are never returned by status APIs or stored in Android.
- Admin can save/replace/remove credentials without an APK update.
- Provider status reports only configured/source/updated metadata.
- Credential changes are audited without recording the secret value.
- Provider gateway continues to resolve secrets server-side.

## Phase 10 — Gemini Command Orchestration & Admin Security
- Device-command execution is now forced through the configured Gemini model; OpenAI is not used as the device-command provider.
- Added Gemini function calling for the safe Android device-command tool, with Android-side final action policy enforcement.
- Added Admin Security service with optional admin password and RFC 6238-compatible TOTP authenticator enrollment.
- When security factors are configured, sensitive admin endpoints require a recent step-up verification.
- Admin unlock sets a short-lived custom-claim verification timestamp; secret/password/TOTP values are not exposed by status endpoints.
- Added native Admin Security UI and provider-secret UI to the existing Admin Control Center.
- No GitHub Actions workflow was triggered.


## Authenticator QR update
- Added native Compose QR rendering for the TOTP `otpauth://` enrollment URI.
- The QR is generated locally on Android from the enrollment URI; no QR image or secret is uploaded to a third party.
- The setup secret and URI remain available as fallback if scanning is unavailable.
