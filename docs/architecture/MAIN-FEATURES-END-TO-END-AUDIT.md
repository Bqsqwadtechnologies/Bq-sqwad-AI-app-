# BQ Sqwad AI — Main Features End-to-End Audit
Date: 2026-09-22

## Scope
This audit is intentionally separate from billing, Play products, pricing, and the commercial catalog. It covers actual product capabilities and their Android → backend → AI/provider → persistence/output paths.

## Verification rule
A feature is only marked end-to-end verified when the UI entry point, Android permission/lifecycle requirements, network/API route, backend authorization/capability gate, provider execution, persistence/output, and failure/revocation handling all exist and can be exercised on a real Android device.

Static source inspection is not treated as proof of runtime success.

## Phase 1 — Main Chat
- Android main chat → Firebase ID token → /ai or /ai/stream → provider gateway → response → UI.
- Streaming is implemented with SSE parsing, incremental UI updates, and a stop/cancellation control.
- Server capability authorization and credit reservation are present.
- Current source reads multiple attachments (up to 5 MB each) and sends extracted text in the prompt; one image can be sent through `/ai/vision`. More than one image is explicitly rejected. The reader supports images and text-like files only; PDF and Office document text extraction is not implemented, and text is capped at 120,000 characters per attachment.
- Current source saves successful non-private chat sessions through `HistoryApiClient`; `HistoryScreen` lists saved chats, opens messages, and deletes records. End-to-end persistence, ownership enforcement, and reopen-continuation still require backend/device verification.
- Remaining gap: file format coverage, atomic handling of multi-file read failures, and end-to-end device verification.
- Status: PARTIAL — core attachment/history paths exist in source; not end-to-end verified.

## Phase 2 — Authentication, Consent, Account Gate
- Firebase email/password and Google sign-in are wired.
- Terms/privacy consent is captured during account creation.
- Optional training consent is stored locally and sent to the backend.
- Protected AI actions require an authenticated Firebase user.
- Status: IMPLEMENTED IN SOURCE; runtime device verification still required.

## Phase 3 — Web Search / Research
- Android calls /web-search.
- Backend authenticates the user and runs the configured search capability.
- Search sources are returned to the Android workspace.
- Deep Research is exposed as a mode.
- Status: IMPLEMENTED IN SOURCE; runtime verification required.

## Phase 4 — AI Live Camera
- Android requests CAMERA permission before camera use.
- Android foreground service declares camera type and camera foreground-service permission.
- Native Camera2 preview and frame sampling are implemented.
- Sampled JPEG frames are sent to /ai/vision.
- Backend checks the AI capability before vision execution and records activity.
- The service is stopped when the workspace is disposed.
- Status: CAMERA PIPELINE IMPLEMENTED; real-device camera/provider test required.

## Phase 4A — Android Screen Viewer
Implemented during this audit:
- Added FOREGROUND_SERVICE_MEDIA_PROJECTION.
- Added mediaProjection to the live-media foreground service declaration.
- Added a dedicated ScreenCaptureSession.
- Screen capture starts only after MediaProjectionManager.createScreenCaptureIntent() and the Android user-consent result.
- Each capture uses a fresh MediaProjection session.
- The session registers MediaProjection.Callback.onStop() and releases the virtual display/ImageReader when Android revokes/stops capture.
- Captured screen frames are sampled and sent through the existing /ai/vision path.
- UI exposes Share screen with AI and Stop screen viewer.
- Status explicitly tells the user that screen sharing is active.
- Status: CODE PATH IMPLEMENTED; Android 14+ real-device consent/revocation/orientation testing required.

## Phase 5 — Live Voice AI
- Android requests RECORD_AUDIO before starting the session.
- Foreground service declares microphone type.
- SpeechRecognizer provides speech input.
- Main AI route generates the response.
- TextToSpeech provides spoken output.
- Interrupt/mute/end controls exist.
- Status: FUNCTIONAL STT/TTS LOOP IN SOURCE; this is not yet a low-latency full-duplex realtime audio transport. Real-device verification required.

## Phase 6 — Personal Intelligence / Life Matters / Learning
- Current source has dedicated `PersonalIntelligenceWorkspace`, `LifeMattersWorkspace`, and `CompleteLearningStudioWorkspace` composables rather than routing all three through a generic task workspace. Learning loads plan/catalog and progress data and submits learning sessions; Personal/Life have domain-specific prompts and API routes.
- Remaining gap: verify all visible controls, progress/history persistence, server entitlement consistency, and real-device workflows. Do not mark complete based on UI presence alone.
- Status: DEDICATED SOURCE FLOWS PRESENT; runtime/backend verification required.

## Phase 7 — AI Companion
- Companion UI supports personality/mode selection, conversation, check-in and memory controls; plan gates are present in the workspace source. Memory and settings are backed by Worker routes, but user-controlled persistence, access enforcement, and plan gates require runtime verification.
- Status: IMPLEMENTED IN SOURCE; runtime persistence and capability tests required.

## Phase 8 — Business AI
Implemented source paths include workspace loading, business assistant, business knowledge, leads, inbox records, training asset metadata/upload/finalization, analytics, and organization-scoped backend storage.
- External social channels are currently represented by workspace/production metadata rather than complete platform adapters.
- CRM, WhatsApp, Instagram, Telegram, Facebook, TikTok, YouTube, SMS, email and other named integrations must not be treated as working merely because their names appear in the UI/catalog.
- Status: PARTIAL — core workspace exists in source; external-channel automation is not end-to-end verified.

## Phase 9 — Organization Studio / Production
Implemented source paths include organization profile/settings, shared knowledge, members, channels, production sources, scenes, destinations, production state/health/events, and AI Director configuration.
- Current limitation: destination registration stores platform metadata and explicitly leaves authentication disconnected; it is not yet a complete encoder/output pipeline.
- Status: PARTIAL — orchestration data layer exists; real broadcast output is not end-to-end verified.

## Phase 10 — Device Intelligence
- Android uses an allowlist for device actions.
- Backend has a separate device-command endpoint and action parser.
- Supported actions include torch, brightness, volume, media controls, and opening selected system apps/settings.
- Brightness routes the user to Android WRITE_SETTINGS authorization when needed.
- Status: IMPLEMENTED IN SOURCE; device-by-device runtime verification required.

## Phase 11 — Privacy / Permission / Foreground Lifecycle
Required checks:
1. Camera permission denied → no camera start.
2. Microphone permission denied → no voice start.
3. Screen capture cancelled → no screen session starts.
4. Screen capture revoked from Android Quick Settings → UI/session stops cleanly.
5. App leaves foreground → camera/microphone foreground-service restrictions are respected.
6. Active camera/voice/screen sessions show an Android-visible foreground notification.
7. No provider secret is placed in the APK.
8. Captured frames are not persisted by the screen viewer itself.
- Status: SOURCE CONTROLS PRESENT; real-device lifecycle matrix still required.

## Phase 12 — Social Streaming
The repository has production destination records for YouTube, Facebook, TikTok, Instagram, RTMP, SRT-style destinations, but those records are configuration metadata, not proof of a working encoder or platform-authenticated broadcast.

A complete implementation requires camera/screen/audio capture, hardware encoding, scene/overlay composition, network transport, destination authentication, stream health monitoring, start/stop state, reconnect handling, and platform API/SDK compliance.

For YouTube specifically, Android provides an official Mobile Live intent that hands the user into YouTube's own live setup flow. That is different from BQ Sqwad AI directly encoding and controlling a custom broadcast. Platform eligibility rules must remain enforced and must never be bypassed.

- Status: NOT END-TO-END VERIFIED.

## Phase 13 — Final Device Verification Matrix
### Camera
- grant/deny/revoke CAMERA
- front/back lens
- preview stability
- frame sampling
- vision response
- stop/start repeatedly
- app background/foreground
- notification presence

### Screen viewer
- Android screen-capture consent
- cancel
- capture current screen
- app/window selection where supported by Android
- AI sees fresh frames
- stop button
- Android Quick Settings revoke
- rotation/resize
- repeated sessions with fresh consent every time

### Voice
- grant/deny RECORD_AUDIO
- recognition
- partial results
- AI response
- TTS playback
- interrupt/barge-in
- mute/unmute
- repeated turns
- stop/end cleanup

### Main chat
- unauthenticated gate
- authenticated request
- streaming
- stop generation
- retry/regenerate
- feedback/report
- share/read aloud
- attachment pipeline
- history persistence/reload/delete

### Business / Organization
- workspace authorization
- knowledge isolation
- training upload/finalize
- assistant response
- lead/inbox persistence
- channel enable/disable
- production source/scene/destination state
- real external connector authentication
- real broadcast output and health

### Generic workspaces
- Personal Intelligence dedicated workflows
- Life Matters dedicated workflows
- Learning Studio dedicated workflows
- Companion memory/history/check-in

## Current conclusion
The source tree contains substantial implementations, but it is not correct to claim that every named main feature is already end-to-end verified. The audit found concrete partial implementations and concrete missing integrations.

The Android screen-viewer path has now been added with explicit OS consent and media-projection foreground-service handling. The next implementation phases must close the remaining gaps rather than simply rename/configure them as complete.

No billing/catalog work is included in this audit.
