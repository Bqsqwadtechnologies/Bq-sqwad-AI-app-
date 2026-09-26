# BQ Sqwad AI — Live Camera, Live Voice & Organization AI Audit

## Current source-level status

- Live Camera now uses one Camera2 owner: `NativeCameraPreview` owns the preview and sampled frames; `LiveMediaForegroundService` owns the persistent foreground-session lifecycle. This removes the competing Camera2 sessions that could make the preview blank or fail to open.
- Live Camera frame analysis is wired from the real preview frames to the authenticated backend and keeps a scrolling activity log.
- Live Voice uses `LiveMediaForegroundService.uiState` as a direct `StateFlow`; no 500–750 ms SharedPreferences polling is required for UI state.
- Live Voice receives microphone/output PCM RMS levels and renders a smooth reactive visual with an idle breathing animation.
- Live Voice transcript events are collected for both user input and BQ Sqwad AI output.
- Screen sharing continues to use the foreground screen-capture session and backend frame analysis.
- Organization AI / Studio is already the user-facing workspace name and is routed as `Workspace.ORGANIZATION_STUDIO`.
- YouTube OAuth/live creation uses encrypted social-account tokens and the encrypted `youtube_client_secret` provider secret. X OAuth/publishing uses encrypted account tokens and the encrypted `x_client_secret` provider secret. Neither secret is stored in the APK.
- Expired YouTube/X credentials now fail explicitly when a usable refresh credential is unavailable instead of sending known-expired access tokens upstream.
- Admin provider configuration already exposes OpenAI as the default route and Gemini as an available fallback through the server-side provider-secret store.
- Admin Test Mode is server-controlled and requires server-verified admin authorization.
- Plan activation/deactivation and Popular-plan changes are server-admin protected.
- The native catalog already falls back to the canonical commercial catalog when Firestore plan data is empty, and the credits endpoint returns a zeroed balance for a new account.
- Organization workspace display is already `Organization AI / Studio`; it should remain a single Organization AI product with Studio/production capabilities inside it.

## Disconnected-feature sweep

### Resolved
- `AdminConfigScreen.kt` — no longer exists in the live UI directory; the active admin surface is `AdminControlSurface.kt` / `AdminControlCenter`, with nested Runtime, Provider Secrets, Plan Catalog, Security and Business Test screens.
- `AdminRuntimeScreen` — reached from Admin Control Center.
- `AdminProviderSecretsScreen` — reached from Admin Control Center.
- `AdminPlanCatalogScreen` — reached from Admin Control Center.
- `BusinessAITestPanel` — reached from Admin Control Center.
- `ProductCatalogScreen` — reached through MainShell Plans & Credits.
- `AccountProtectionScreen` — reached from Settings.
- `LegalCenterScreen` — reached from Settings and feature privacy actions.
- `WorkspaceAccessSnapshot` — used by MainShell to gate workspace navigation from server catalog + verified entitlements + server Admin Test Mode.

### Deliberate architecture
- Workspace-specific screens are routed through `WorkspaceScreen`; Organization AI / Studio, Business AI, Learning Studio, TruthGuard AI, Life Matters, Personal Intelligence, Companion, Live Camera and Live Voice each have their own workspace implementation.
- Advanced capabilities remain selectable from Main Chat rather than requiring a separate Advanced AI workspace.

## Runtime verification boundary

Source inspection confirms the requested wiring, but a real Android device/emulator session, Firebase Auth account, live Firestore data, Google Play sandbox purchase and provider OAuth credentials are required to mark runtime verification PASS. Those checks must not be represented as passed merely because the source compiles.

## Required live tests

1. New Firebase account -> consent screen -> accept -> Main Chat -> protected chat succeeds.
2. Reject consent -> Firebase sign-out -> Sign In.
3. Live Camera -> Android permission -> real preview -> frame analysis -> activity log.
4. Live Voice -> microphone permission -> realtime session -> input/output reactive visual -> transcript.
5. YouTube OAuth -> encrypted refresh token -> create live broadcast -> native RTMP/RTMPS stream -> transition/status.
6. X OAuth2 PKCE -> encrypted refresh token -> token refresh -> publish.
7. Organization AI / Studio -> verified organization entitlement -> production controls.
8. Admin Test Mode -> only server-verified admin gets entitlement bypass.
9. Non-admin -> no verified paid entitlement -> workspace remains behind the plan gate.
10. Google Play purchase -> backend verification -> entitlement -> workspace access.


## Organization AI / Studio checklist

| # | Capability | Source status |
|---|---|---|
| 1 | Named production sources / scenes | **Wired server-side**: source and scene registries persist through Organization Production routes. Native StreamPack still has one active local camera pipeline, so true physical multi-camera live switching requires a verified multi-source broadcaster path. |
| 2 | AI Director / automatic switching | **Partially wired**: AI Director and auto-switch controls persist and the production-director AI request is connected. Automatic physical switching is not claimed complete until the broadcaster can switch real sources during an active stream. |
| 3 | YouTube live destination | **Wired server-side**: OAuth account tokens and client secret use the encrypted secret store; live broadcast/stream creation, ingestion credentials, transitions and status are implemented. |
| 4 | Facebook / other simulcast destinations | **Configuration layer wired**; simultaneous native multi-destination broadcast is not claimed complete because the current native broadcaster has a single StreamPack output. |
| 5 | Graphics overlays / lower thirds | **Deferred**: requires real-time video compositing in the active StreamPack pipeline rather than a metadata-only UI. |
| 6 | Live captions / translation | **Partially wired** through AI capabilities, but continuous production caption/translation injection into the outgoing video is not yet a verified broadcaster feature. |
| 7 | Scheduling / recurring programming | **Wired server-side** for organization-scoped scheduled AI tasks; a provider-specific starting-soon live broadcast countdown still requires live provider/device verification. |
| 8 | Stream health / reconnect | **Health model wired** with fallback/reconnect policy fields and health/event endpoints; automatic physical camera/output recovery requires real-device/provider testing. |
| 9 | Moderated viewer chat / Q&A | **Deferred**: requires a live-chat provider ingestion/moderation path and persistence beyond the current organization/channel model. |
| 10 | Donation / giving QR overlay | **Deferred**: requires a real organization donation URL/provider and video compositing path; no fake QR or donation state is inserted. |
| 11 | Post-event summaries / highlights | **Partially wired** through AI production capabilities; automated media extraction/highlight rendering needs a verified media-job pipeline. |
| 12 | Attendance/check-in | **Organization member roles are wired**; viewer attendance/watch-time collection requires provider analytics events before it can be considered real. |
| 13 | Branded sensitive-action confirmation | **Not yet fully wired** as a reusable organization-owned confirmation flow for all listed destructive/export/refund/ownership actions. |
| 14 | Server-side sensitive-action roles | **Partially wired** through organization/member authorization; each sensitive action still needs its dedicated endpoint-level role enforcement verified before release. |
| 15 | Session analytics | **Deferred** until real provider analytics events are ingested; the current production state/health records are not treated as viewer analytics. |

This table intentionally separates real server/client wiring from provider-dependent features that cannot honestly be marked complete without the required live integration.


## Latest implementation pass — 2026-09-26

- Authentication handoff fixed: Sign-in/Sign-up no longer submits a pending protected Main Chat request immediately after Firebase authentication. The root AuthGate now remains authoritative: signed-in users must complete server-verified Terms/Privacy consent before Main Chat is usable.
- Consent rejection signs the Firebase account out; training consent and required consent continue to write to the same `userConsents/{uid}` record used by the backend.
- Email verification remains optional rather than an access blocker; the app still sends/recommends verification without creating a second mandatory gate.
- Live Voice now reserves, commits, and reverses credits through the same shared `creditBalances` pool used by Main Chat/Live Camera. Early failure paths explicitly reverse reservations.
- YouTube live-start now uses the same shared credit pool and server-side entitlement/capability checks.
- Business AI now exposes an entitlement-derived dashboard context containing verified plan name, access end date, trial eligibility, limits, included features and Popular state.
- Organization AI / Studio destination UI is limited to the currently honest product surface: YouTube Live and X post-event publishing. Facebook/Instagram/TikTok are not presented as live-ingest destinations.
- OpenAI remains the server-side default provider; Gemini remains an available fallback. Provider and YouTube/X secrets continue to come from the encrypted server-side secret store and are never returned to Android.
- Google Play remains authoritative for real subscription cancellation/renewal. Admin Test Mode is the server-controlled QA bypass; it does not fabricate a paid Google Play entitlement.

