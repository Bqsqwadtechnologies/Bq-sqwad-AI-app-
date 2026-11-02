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


## Billing, chat durability, media and collaboration extensions

### Admin-owned fixed feature prices
- Fixed-output feature prices are read from `adminAdvancedOptions/capability_{feature}`; the canonical field is `creditCost`. Missing or invalid prices must create `adminAlerts/missingPrice_{feature}` and fail closed with a safe `feature_temporarily_unavailable` response (HTTP 503), never silently substitute a one-credit charge.
- Seeded defaults, written only when the document is absent: `image_generation` = 1.5 credits, `image_edit` = 1.5 credits, `chat_background_upload` = 2 credits. These values are administrator-editable and must be read on each operation; a seed must never overwrite an existing administrator value.
- Text chat, vision, attachments, Personal Intelligence and team chat are token-metered from actual provider usage. Reservations are estimates; final settlement uses uncached input, cached input, output tokens, published model pricing/weight, and the configured customer multiplier. Failed or interrupted calls reverse unused reservations.
- Media message fields: `attachments[]` and `generatedMedia[]` entries retain stable IDs, media type, display name, MIME type, storage URL/path, dimensions where applicable, creation timestamp, and generation/edit metadata. URLs are owner-scoped cloud-storage objects and are restored from history.
- Finished assistant messages may include `feedback` (`liked: true | false | null`), `sources[]` (`title`, `url`, `domain`, `favicon`, `snippet`), and `status` (`complete | interrupted`). These fields are persisted with the shared `chats/{chatId}` message snapshot and synchronized between Android and web.

### Connector permissions
- `composioPermissions/{uid}__{toolkit}`: `accessMode` is `read_only` or `full_access`; permissions are user- and toolkit-scoped and are enforced server-side.
- `composioSessions/{uid}__{toolkit}` records connection/session state. Client payloads may set `conversationUsedConnectors: true` to retain connector context across follow-up turns, but never grant permissions.

### Teams and projects
- `teams/{teamId}`: `name`, `ownerUid`, `plan`, `creditPoolId`, `memberCount`, `createdAt`.
- `teams/{teamId}/members/{uid}`: `role` (`owner | admin | member | viewer`), `displayName`, `photoUrl`, `status`, `joinedAt`, optional `spendLimit`.
- `teams/{teamId}/projects/{projectId}`: `name`, `description`, `createdBy`, `createdAt`, `lastMessageAt`, `archived`.
- `teams/{teamId}/projects/{projectId}/messages/{messageId}`: `authorUid` (or `ai`), `authorName`, `text`, `attachments[]`, `createdAt`, optional `tokenUsage`, `credits`, `sources[]`, and feedback/status fields.
- `teams/{teamId}/usage/{yyyymm}_{uid}`: per-member `credits` and `tokens`; writes should be sharded or request-scoped under high concurrency. Team usage must debit the approved team pool/charge account, and each AI response is settled by actual token usage.

### Upload validation and long-paste attachments
- Shared upload settings must identify allowed MIME/extensions, maximum byte size and maximum image dimensions. The UI validates before upload and emits a friendly, non-technical requirement message with the measured file size/type/dimensions.
- Pasted text at or above `PASTE_TO_FILE_LINE_THRESHOLD = 50` is persisted as a `.txt` attachment with a generated stable ID, filename, line count and preview metadata; it follows the same upload/storage ownership checks as other attachments.
- Background updates persist the versioned storage URL in the shared profile/theme settings, and clients refresh profile state and invalidate image caches after a successful receipt.

### Usage metering and search sources
- Search responses persist `sources[]` with the assistant message and use numbered inline citations that map to the source list. Search operation price is the admin-configured `web_search`/deep-research capability setting, plus the actual token charge for any synthesized answer.
- Rate-limit buckets are separated by route group; history, sync and connector polling must not consume the chat bucket. Provider retries and capacity errors are logged with safe user-facing codes.


## Upload, generated media, error and pricing-audit contract (2026-10-28)

- Shared Firebase Storage bucket: `bqsqwadai.firebasestorage.app`. Owner uploads use `users/{uid}/uploads/**` (25 MiB maximum; supported image/audio/video, plain text, PDF, JSON, ZIP and office-document MIME types). Generated files use `users/{uid}/generated-files/**` (50 MiB maximum). Profile images and wallpapers use `avatars/{uid}/**` (5 MiB maximum, image MIME type). Storage rules retain the final deny-all rule; writes must be authenticated and UID-scoped. Admin notification branding uses `branding/notification-logo/**` (public reads, verified admin-only PNG/JPEG/WebP writes up to 5 MiB); the website and Android Storage rules files are kept identical. Firebase Storage rules are deployed from the website repository's `Deploy Firebase Storage rules` workflow, which requires the `FIREBASE_SERVICE_ACCOUNT` Actions secret for project `bqsqwadai`.
- Generated media message records must retain a durable storage path and stable media metadata (type, display name, MIME type, dimensions when known, timestamp and generation/edit metadata). The path—not a transient local URI—is the cross-platform reference for history restoration.
- Server-facing error codes are distinct: `rate_limited`, `provider_capacity`, `provider_key_missing`, `provider_quota`, `model_price_missing`, `feature_price_missing`, `usage_unavailable`, `insufficient_credits`, and `internal_error`. A generic HTTP 5xx is not by itself evidence of provider capacity. When a server reference exists, clients should preserve it rather than replace it with a locally generated reference.
- The admin-only Worker endpoint `GET /admin/pricing/audit` reports missing or invalid `creditCost` values in live `adminAdvancedOptions/capability_*` configuration and writes `adminAlerts/missingPrice_<feature>` documents. Both admin UIs still need to display these results before this is considered complete.
- Connector state is shared through the authenticated Worker connector session and permission endpoints. Permission mode values are `read_only` and `full_access`; an OAuth connection does not itself grant full access. Clients must refresh connection and permission state after returning from OAuth.
- Rules deployment workflow: `.github/workflows/deploy-firebase-rules.yml`; requires GitHub secret `FIREBASE_SERVICE_ACCOUNT`. Repository presence of the workflow does not prove the secret is configured or that production rules have deployed.


## Presence ping — current Worker route
- Android and website clients may call authenticated `POST /presence/ping` with `{ platform }`.
- Worker verifies Firebase ID token, rate-limits per user, and writes `presence/{uid}` with `platform`, `lastSeen`, `signedIn`, and `updatedAt`.
- Active presence window is 90 seconds. Do not include message content, OTP, BVN, NIN, credentials, or connector secrets in presence documents.

## Business workspace navigation
- Android Business workspace exposes Playbooks and Connect tools entries. Playbooks use the shared website native API; tool connection handoff opens `/settings#connectors`, where the authenticated Composio OAuth flow is implemented.
- Empty/missing tool ID lists intentionally resolve to the broad optional catalog. KYC/BVN/NIN remain opt-in and must not be required for workspace access.
- Tool discovery/catalog visibility is not authorization: every connector execution must still verify the user's workspace entitlement and connection server-side.

## Implementation boundary (2026-11-01)
- Worker chat accepts `modelKey`: `bq-7-star` defaults to `openai/gpt-6-luna`; explicit `bq-codron` selects `openai/gpt-5.6-luna`. A published `modelRegistryId` takes precedence.
- Gemini fallback is not yet active: the current provider helpers attempt only the primary provider; the separate `orchestrator-native.js` helper is not wired into `index.js`. Implement fallback eligibility, admin cost rules, and one-settlement behavior before enabling it.
- `usageDaily` sharding helper exists; complete instrumentation of every feature settlement path is still outstanding.
- Free-credit notification delivery, encrypted vault persistence/hash-chain integration, teams v2 pooled billing, organization parity, and full client notification inbox behavior require runtime verification before being called complete.



## Shared notifications and My Tasks
- `notificationPreferences/{uid}` is shared across website and Android. Worker `GET/PUT /notifications/preferences` reads/writes account-level in-app and push toggles plus muted categories. Android Settings now exposes the master notification toggle and category-level mutes for the same categories as the website. The Android master toggle still controls in-app and push together; website exposes those delivery channels separately.
- Worker `GET /tasks/my` returns only records assigned to the verified UID; `PATCH /tasks/my/status` checks assignment and restricts status to `open`, `in_progress`, `blocked`, or `done`. Android My Tasks is connected to these endpoints.

## Training privacy
- Training candidates require explicit consent and a server-owned adult-only gate. Parental consent alone cannot qualify a minor.
- Business, organization, team, private/secret conversations and any media/attachment-bearing chat are excluded; identifiers are redacted before candidate review.
- Training remains a prepare/review/export workflow only. It does not fine-tune or deploy a model.


## Shared team memory — 2026-11-02
- Canonical records live at `teams/{teamId}/memory/{memoryId}` in the shared Firebase project.
- Website server functions and native Worker routes expose list/add/update/delete. Reads require an active team membership; writes require an active owner/admin membership. Android TeamWorkspace uses the native Worker routes and does not keep a separate local memory store.
- Team memory is visible to active team members and must not be mixed into personal memory or another team's context.

## Team monthly spend limits — 2026-11-02
- Website owner/admin controls write `spendLimitCredits` on `teams/{teamId}/members/{uid}`; team usage rollups are `teams/{teamId}/usage/{YYYYMM}_{uid}`.
- Website AI debit atomically reserves member monthly usage before debiting `teamCreditPools/{creditPoolId}`, and rolls back the usage reservation if the pool debit fails.
- Android TeamWorkspace now exposes owner/admin monthly member cap controls through authenticated `POST /native/teams/member/spend-limit`; the Worker validates active membership, admin role, target membership and owner protection before writing the cap.
- Native Worker AI settlement now uses Firestore update-time compare-and-set to reserve `teams/{teamId}/usage/{YYYYMM}_{uid}` before debiting the shared team pool. If the pool debit fails, it rolls back the member usage reservation. A member cap blocks settlement and the uncharged provider result is not returned.
- Website and native Worker now share the member cap document and monthly usage path. This is implemented in source but NOT tested for concurrent requests or verified live; do not treat it as release-verified.
- Native Worker team AI settlement fails closed when a successful provider response lacks usage data or canonical token pricing: it does not return the generated response as a free answer. This behavior is source-only until Worker tests and live billing checks are run.

## Android conversation export
- Chat History can share conversation exports as TXT, JSON or CSV using the Android system share sheet. Export records contain message text, attachment names and generated-image links; original attachment binaries are not embedded and must be retained/downloaded separately where required.

## Current Phase 2–4 source integrations — 2026-11-02
- The implementation-boundary note above is historical: current Worker index.js imports and calls runWithFallback for eligible AI routes. The current orchestrator attempts the configured primary provider and at most one admin-configured eligible Gemini/OpenAI fallback, with token usage settled against the provider that actually answered. Do not interpret this as live-provider verification.
- Worker daily free-credit grant now calls notifyDailyFreeCredit only after the atomic grant/transaction commit succeeds. It writes an in-app notification; push tokens are not guessed and real-device push delivery remains unverified.
- Worker mounts authenticated GET /business/storage. It resolves the caller's canonical Business workspace, uses the same Business plan-context logic as businessWorkspace for expiry/test-session status, and returns usedBytes, totalBytes, add-on threshold state, renewal-only state and serverNow. Android BusinessWorkspaceScreen calls WorkspaceApiClient.businessStorage(workspaceId) and renders BusinessStorageMeter and BusinessPlanExpiryBanner. A workspace with no configured quota may correctly show no meter values.
- Website team records now persist an optional HTTPS logo URL and a blue-family theme color (`blue`, `indigo`, `violet`, `cyan`). The website supports creation and owner/admin editing; team detail displays the stored branding. Android team branding parity and main-chat-wallpaper inheritance are not yet implemented.
- Android Main Chat's three-dot menu is connected to SecretChatsDialog and includes Secret Chats, Share, Rename, Clear, Delete and Download conversation. Download uses Android's document picker and excludes private-session turns. Chat History separately supports TXT/JSON/CSV export. Android Add-to-Organization-project and PDF/Word/Markdown export are still outstanding.
- Android SecretChatsDialog and HistoryScreen.SecretChatVaultDialog use the shared Worker ciphertext-only secretChats endpoints and the website's PBKDF2-HMAC-SHA256/AES-GCM blob contract. They support password/recovery-code move, unlock, delete, five-minute auto-lock and FLAG_SECURE. WebAuthn/biometric unlock and cross-device crypto QA remain outstanding.
- Website Main Chat's generic generated-file card currently supports download/share and image/PDF preview. It does not provide format-specific Office/spreadsheet/code viewers, editing or regeneration.
- Website Main Chat, Team AI and Organization AI show client-side elapsed timers. Reconnect-safe server taskStarted/phase/taskEnded events are not yet implemented.
- Website Admin now mounts Training Storage aggregate metrics and consented-candidate review/export. Android admin source remains retained until every admin capability has a verified website equivalent.
- No build, tests, lint, authenticated integration QA, device/browser QA or deployment was run during this source continuation. The requester will run validation.

## Phase 2–4 source continuation — 2026-11-02 (latest source state)
- Business storage API contract correction: `GET /business/storage?workspaceId=...` verifies the Firebase user and resolves the requested workspace, but reads the current account-scoped storage record at `businessStorage/{uid}`. Plan expiry is taken from the resolved `workspace.planContext.expiresAt` (including an active admin test session) and the endpoint returns `serverNow`. Do not key the current quota record by workspace ID.
- Worker super-admin `GET /admin/training-storage` pages aggregate metadata from `businessTrainingAssets`, adult-consented `userConsents`, active `consented_contributions`, and `agentTrainingCandidates`; it returns aggregate counts/bytes only and signals truncation at the 500-record-per-source cap. The website Admin Training Storage panel displays these metrics alongside candidate review controls.
- Android main-chat overflow menu now includes branded TXT, JSON and CSV export through the Android share sheet. It does not create native PDF/Word files. The secret-chat dialog's own `FLAG_SECURE` protection remains authoritative; no redundant Private Chat flag should clear it.
- Website team creation and discovery share logo URL and theme color metadata. Allowed theme values are `blue`, `indigo`, `violet`, and `cyan`; logo URLs are HTTPS-only. Android team logo/theme rendering is still outstanding.
- Website BQ-Codron hint dismissal/seen state is persisted per conversation across browser sessions.
- All items above are source-only. Do not infer production deployment, test success, or cross-client runtime parity from these commits.



## Monthly feedback campaign — 2026-11-02 (website-only)
- Website root shell shows the monthly survey to authenticated non-anonymous users when the campaign is enabled and the configured receiver is still a verified administrator.
- One submission per account per UTC month is enforced atomically at `monthlyFeedback/{YYYY-MM}_{uid}`.
- Submission creates an in-app notification at `notifications/{receiverUid}/items/{notificationId}`; Admin > Monthly feedback campaign lists the latest 100 records routed to the signed-in administrator.
- The admin config is stored in `adminAdvancedOptions/monthlyFeedback`. The receiver must be a verified administrator.
- Android survey UI, push/email delivery, external scheduled campaigns and runtime verification are not implemented.


## Team spending-limit contract — source re-audit 2026-11-02
- Website and Android TeamWorkspace provide owner/admin monthly member cap controls.
- Android calls authenticated Worker `POST /native/teams/member/spend-limit`; Worker checks active admin membership and atomically updates the target member's cap.
- Worker AI settlement reserves `teams/{teamId}/usage/{YYYYMM}_{uid}` atomically before debiting `teamCreditPools/{poolId}`; a failed pool debit attempts to roll back the reservation. Website AI settlement uses the corresponding website server reservation/debit flow.
- These paths are implemented in source but have not been tested under concurrent requests, cap changes, month rollover, insufficient pool balance or cross-client live usage. Do not treat this contract as production-verified.


## Business Inbox paging — 2026-11
- Android and Worker use the authenticated `GET /business/extended` contract with `workspaceId`, `inboxOffset` and `inboxLimit`.
- The Worker derives the caller UID from the Firebase ID token, checks workspace ownership/membership and Business plan entitlement, clamps page size to 1–100 and offset to 0–100000, and returns `inboxPage.offset`, `limit` and `hasMore`.
- Android initially loads 30 inbox records and requests additional pages on demand, merging records without replacing the existing list.
- The API uses Firestore offset paging, not a cursor snapshot. Very large inboxes may incur increasing read/latency costs; a future cursor-based query should be considered after index and runtime verification.
- Source implementation only. The contract test has been added but not run; Android build, Worker tests/check and authenticated staff workflow QA remain the user's validation steps.


## Token pricing unit contract

Customer token prices are canonical **credits per one token**: `inputCreditsPerToken`, `cachedInputCreditsPerToken`, and `outputCreditsPerToken`. Admin writes and the shared settlement calculation use these per-token values. Existing Firestore documents containing `inputCreditsPer1k`, `cachedInputCreditsPer1k`, or `outputCreditsPer1k` remain readable through the shared conversion helpers and are normalized to per-token values; legacy mirrors remain only for compatibility. Provider USD telemetry (`*UsdPerToken`, `*UsdPerMillion`, or USD-per-1k fields) is a separate internal cost measure and must never be confused with customer credit prices.
