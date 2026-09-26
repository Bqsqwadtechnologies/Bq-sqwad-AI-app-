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
