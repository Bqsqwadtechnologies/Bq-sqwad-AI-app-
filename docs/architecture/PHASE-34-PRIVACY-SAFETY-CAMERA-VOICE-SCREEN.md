# Phase 34 — Privacy & Safety Controls for Camera, Voice & Screen
## Objective
Centralize sensitive-capability controls.
## Controls
- Camera permission.
- Microphone permission.
- Location permission.
- Screen capture consent.
- Background behavior.
- AI speaking state.
- Data retention indicators.
- Session stop controls.
## Android rule
MediaProjection is an explicit user-consent capability and must be requested for the appropriate capture session.
## Acceptance
Stopping a sensitive session actually stops its capture/processing path; UI state cannot claim stopped while capture continues.