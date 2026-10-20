# BQ Sqwad AI Cross-Platform Data Contract

Status: implementation baseline; runtime verification remains required.

## Identity and trust
- Firebase Authentication UID is the stable account key on both clients.
- Every server API must verify the Firebase ID token and derive UID from the verified token. Never accept a client UID as authorization.
- Browser and Android clients must not access private Firestore documents directly; server-side services enforce ownership.

## Shared chat records
- Authoritative collection: Firestore `chats/{chatId}`, managed by the website server functions in `src/lib/chat.server.ts`.
- Ownership: `uid` must equal the verified Firebase UID.
- Shared fields: `id`, `uid`, `title`, `preview`, `messages` (JSON string using AI SDK message shape: `{id, role, parts}`), `messageCount`, `createdAt`, `updatedAt`, `pinned`, `archived`.
- Android transport: `GET /api/native/chats`, `GET /api/native/chats?chatId={id}`, `POST /api/native/chats`, `PATCH /api/native/chats` (title/pinned/archived), `DELETE /api/native/chats?chatId={id}`. All require `Authorization: Bearer <Firebase ID token>`.
- Old Android-only records in Worker `chatHistory/{id}` are imported on authenticated list requests. Import is non-destructive; deletion of the shared record also attempts to remove its legacy copy.
- The migration is best-effort and should be monitored before any legacy collection is retired.

## Shared account finance and provider-specific billing
- The Worker credit engine and website credit engine both read/write the user profile keyed by `users/{uid}`; credit reservations, ledger entries and purchases remain server-authoritative.
- Paystack remains the website's payment provider and Google Play Billing remains the Android app's payment provider. Neither provider is replaced or used to bypass the other provider's verification.
- Paystack's existing internal `planId` values and Paystack plan codes, and Google Play's existing `productId`, `basePlanId`, `offerId`, and mapped plan IDs are immutable integration identifiers. Do not rename, regenerate, or remap them as part of UI, admin, or synchronization work.
- Each provider must verify its own payment/renewal/refund/revocation using its server-side checker. Shared entitlements are projections of verified records, never client claims. A Paystack subscription does not become a Google Play purchase and a Google Play purchase does not become a Paystack transaction.
- The native billing status endpoint returns safe, read-only Paystack subscription summaries and a sanitized Google Play entitlement summary. It never returns purchase tokens, private provider secrets, or service-account data.
- Expired general subscription tiers must not remain entitled merely because a stale `subscriptionPlan` string remains on the profile. The server-authoritative subscription period controls active access; permanent credits are not deleted when a subscription expires.
- Admin catalog/configuration must preserve all existing IDs and provider mappings. Administrative edits must not silently rewrite live identifiers or retroactively alter verified receipts.

## Saved memories and settings
- Canonical saved facts use owner-scoped `aiMemories/{id}` records. Website memory listing imports legacy Worker `userMemory/{uid}.items` with stable IDs, then removes only successfully migrated items from the legacy array. Worker memory reads include canonical records; Worker-created facts are written to `aiMemories`. Memory enablement is shared through `users/{uid}.memoryEnabled`, with the legacy flag used only when the profile has no explicit preference. Runtime migration and deletion behavior still requires testing.
- Device permissions and active camera/microphone sessions are device-local and must never be synchronized as granted permissions.

## Reliability and security
- Preserve stable IDs, retry-safe requests and owner checks.
- Use server-generated timestamps and server-side credit/account entitlement checks.
- Errors must not expose tokens, secret values or other users' records.
- No workflow/build/release automation is initiated by this implementation.


## Canonical authority and synchronization contract (2026-10)

### Canonical data homes
- `users/{uid}` is authoritative for account identity, credits buckets, subscription projection, `createdAt`, `lastActiveAt`, and shared `featureSettings`.
- `creditTransactions` is the canonical cross-platform credit ledger. Every entry includes a source/platform marker such as `android` or `web`. Worker `creditLedger` is legacy/read-only after migration.
- `providerUsage` is the canonical provider usage and cost history. Worker `aiUsageLedger` is legacy/read-only after migration.
- Billing configuration is edited only in website admin: `config/usageBilling`, `tokenPricingRules`, `config/providerConnections`, `config/pricing`, and `config/aiModels`. Worker caches canonical configuration for about 60 seconds. Missing configuration must be visible to administrators.
- Catalog authority is `config/pricing`, `config/businessPricing`, `organizationPlans`, and the public catalog contract. Existing Google Play mappings in `playCatalog/config` are immutable pass-through identifiers, never regenerated or remapped.
- `chats` via `/api/native/chats` is the shared history store; `aiMemories` is the shared saved-memory store.
- Website business collections and website `organizations`, `studio*`, and `studioPayg*` are canonical. Worker-only business and organization data must be copied idempotently with stable IDs and remain available read-only until reconciliation is confirmed.
- File metadata is canonical in `userFiles/{id}`; normal file bytes are in Firebase Storage under `users/{uid}/files/...`. Large generated media remains in Supabase Storage `generations`.
- Push tokens are canonical in `pushTokens/{token}`; consent state is `users/{uid}.consents` with history in `consentEvents`.
- Companion, Learning, TruthGuard, Life Mathis, and Frontier data use the website's canonical models and authenticated native endpoints. Device permissions, camera/microphone state, flashlight and local audio volume remain device-local.

### Canonical billing formula
```
customerUsd = providerCostUsd * (1 + markupPercent / 100) * (1 + feePercent / 100)
credits = round5(customerUsd / usdPerCredit)
```
Defaults apply only when the relevant config document is missing: markup 600%, fee 0%, USD per credit 0.1199. A missing-document fallback must emit an administrator-visible warning. Do not silently substitute a different credit value or multiplier.

- Token charges use provider-reported uncached input, cached input and output token counts, and the active matching `tokenPricingRules` rule. Do not impose a minimum charge.
- Voice, camera, screen-share and transcription costs use canonical `config/providerConnections` rates and elapsed seconds; do not round elapsed time up to whole minutes. An explicit `creditsPerMinute: 0` means free.
- Fixed-price generation rules are final customer prices and receive no second markup. Reference fixed prices: web search 1.7 credits and deep research 5 credits.
- Live plan-included features cost zero while entitlement is active. Deduction order is daily-free, subscription, permanent; refunds return to the original buckets. Configured admin accounts are unlimited.
- One charge creates one `creditTransactions` row and one `providerUsage` row with a breakdown. Responses expose safe `creditsCharged` and breakdown fields; provider USD rates and secrets are never exposed to normal clients.
- Web search runs only when requested/needed, charges once per question only if usable sources were returned, and appears as a separate fixed-price line item.

### Shared settings and activity
- Synced settings live on `users/{uid}`, preserving existing top-level `memoryEnabled`, voice fields and `theme`, and adding `featureSettings`: `web_search`, `memory`, `notifications`, `history`, `auto_save`, `streaming`, `voice_responses`, `save_files`, `personalization`, `camera_enhance`, `companion_memory`, `truthguard_sources`, and `text_scale`.
- Changes carry `updatedAt`; newest timestamp wins. Device-local permissions and live device state are excluded.
- Both clients update `lastActiveAt` on open/foreground, throttled to at most hourly. First sync sets `createdAt` from Firebase Authentication creation time and never overwrites it.
- `POST /api/native/me/activity` accepts a verified Firebase ID token, records activity and may register an Android token as `platform: "android"`.

### Notifications
- Android tokens receive birthday and comeback reminders from Android local alarms only. Website push re-engagement must skip users with Android tokens and serve web-only users only.
- Activity age is based on `max(lastLogin, lastActiveAt)`; comeback threshold is two days. Respect notification preference, cooldowns, and 09:00–20:00 local quiet hours when timezone is known.
- Birthday age/milestones derive from canonical `users/{uid}.createdAt`.

### API and file contracts
- Native endpoints verify Firebase ID tokens and derive UID from the token; records are owner-scoped and responses contain no secrets.
- `GET/PATCH /api/native/settings`, `GET /api/native/credits`, `GET/POST/PATCH/DELETE /api/native/files`, `POST /api/native/me/activity`, and native organization/Studio endpoints reuse website server functions rather than duplicating business logic.
- File uploads are limited to 25 MB, use server-issued signed URLs and owner-scoped paths, and preserve legacy File Box records until successful read-back. Account deletion covers all file stores.
- Paystack and Google Play fulfilment remain separate verified paths. Store-localized Google Play prices are authoritative in Android; website pricing applies to Paystack only.

### Immutable integration identifiers
- Existing Paystack plan IDs/codes, internal plan IDs, Google Play product/base-plan/offer IDs, mappings and credit grant amounts are immutable. Admin edits may change display names, features, credit amounts where permitted by the existing mapping policy, and display prices, but may not write changed identifiers. Snapshot tests must protect current IDs in each repository.
- Catalog responses include a monotonically increasing version so Android can refresh without an app release. Missing-side IDs are reported, not recreated.

### Migration safety and legacy data
- Use dual-read and idempotent one-way copy with stable IDs. Never delete or overwrite legacy data before reading back and confirming the canonical replacement.
- `creditLedger`, `aiUsageLedger`, `chatHistory`, `notificationTokens`, `fileBox`, `businessWorkspaces`, and `organizationWallets/organizationWalletLedger` are migration sources; treat them as read-only until reconciliation is confirmed.
- Organization wallet reconciliation requires a read-only balance report first, followed by idempotent import with stable IDs and an audit entry. Never create or lose money.
- No deployment, workflow execution, or live infrastructure mutation is part of source work.
