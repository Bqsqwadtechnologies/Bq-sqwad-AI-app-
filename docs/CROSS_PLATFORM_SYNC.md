# BQ Sqwad AI Cross-Platform Synchronization Contract

The canonical shared contract is maintained in the website repository at `docs/cross-platform-sync-contract.md`.

## Current Android integration
- Firebase Authentication UID is the account identity.
- Chat History now uses the authenticated website endpoint `https://bqsqwadai.com/api/native/chats`, which writes to the same `chats/{chatId}` records used by the website.
- Requests send a Firebase ID token in the Authorization header. The server derives the UID from the verified token; the Android client does not supply an ownership UID.
- Legacy Android chat history in Cloudflare Worker's `chatHistory` collection is imported by the website API on authenticated list requests. The migration is best-effort and non-destructive; the old copy is only removed on an explicit shared-chat deletion.
- The Cloudflare Worker remains authoritative for its existing AI, credit-reservation, billing verification and other protected operations. Do not move provider secrets or billing authority into the Android app.

## Provider-specific billing and immutable identifiers
- Website recurring subscriptions remain on Paystack. Android in-app purchases remain on Google Play Billing. Each provider uses its existing server-side checker and authoritative records.
- Never change any existing internal plan ID, Paystack Plan Code, Google Play product ID, base-plan ID, offer ID, or purchase reference. UI/catalog polish must not rename or regenerate them.
- The website's authenticated `GET /api/native/billing` response includes a safe Paystack subscription summary and sanitized Google Play entitlement summary. It intentionally excludes purchase tokens and provider secrets.
- The website entitlement resolver now expires the general subscription-derived tier when its authoritative subscription period has ended; this does not remove permanent credits.

## Known remaining synchronization work
- Saved memories now use website `aiMemories/{id}` as the canonical collection. The website server imports legacy Worker `userMemory/{uid}.items` using stable IDs, and Worker memory operations read/write the shared collection. `users/{uid}.memoryEnabled` is the shared preference. This migration and delete behavior still requires runtime tests.
- Account-level settings, generated-content history, admin-dashboard read-only billing-ID visibility, and conflict/version metadata still need implementation and cross-platform runtime verification.
- No GitHub Actions, builds or releases were triggered.
