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
