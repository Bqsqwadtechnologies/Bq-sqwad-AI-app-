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
