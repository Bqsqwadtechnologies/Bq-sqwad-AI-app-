# Phase 8 — Google Play Billing Architecture

## Scope
Native Android subscriptions and entitlement synchronization for BQ Sqwad AI. This phase is separate from Paystack website checkout. Paystack remains website-only.

## Required purchase lifecycle
1. Load the server-authoritative product catalog.
2. Query Google Play Billing for products offered to the signed-in account.
3. Show only products that are actually returned by Google Play and enabled by the server catalog.
4. Launch the Play purchase flow.
5. Receive purchase updates through the billing client.
6. Send purchase token and product identifiers to the authenticated backend.
7. Verify the purchase with Google Play on the server before granting entitlement.
8. Acknowledge/consume according to the product type and Google Play requirements.
9. Persist entitlement state and expiration/renewal state server-side.
10. Refresh native UI from server entitlement state.
11. On renewal, restore services that were disabled specifically by expiry.
12. On expiry/revocation, disable entitled services without deleting user data.

## Security
- Never treat an Android callback alone as proof of payment.
- Never trust client-provided price, entitlement, expiry, or renewal state.
- Never store Google Play service-account credentials in the APK.
- Keep purchase-token verification server-side.
- Keep product IDs mapped to the server catalog rather than hard-coding commercial pricing in UI.
- Avoid logging purchase tokens or other sensitive billing material.

## Product and entitlement model
The existing catalog/entitlement system remains the source of truth for plan names, features, limits, credits, and future pricing.
Google Play product IDs map to catalog entries.
Entitlements must include at least:
- product/plan identifier
- active state
- start time
- expiration time when applicable
- auto-renewing state when available
- source = google_play
- verification status
- last synchronization time

## Restore and lifecycle
- Restore purchases after sign-in and app start.
- Process subscription renewal/revocation events from the backend provider integration.
- Preserve personal memory, organization data, business data, chats, files, and history when an entitlement expires.
- Re-enable only services that were disabled because of plan expiry.
- Manual administrative disables remain disabled until explicitly changed.

## UI
Native billing UI must clearly distinguish:
- current entitlement
- available Play products
- purchase pending
- purchase successful and verified
- restore/sync
- expired entitlement
- renewal/auto-renew state

The app must not claim a purchase succeeded until server verification succeeds.

## Current implementation boundary
Billing Library is already present in the Android project, and the repository has server-side Google Play integration work. Before calling Phase 8 complete, the purchase, verification, acknowledgment, entitlement refresh, renewal, expiry, and restore paths must be exercised against real Google Play test products and verified end-to-end.

No commercial prices are invented in this phase.
