# Organization AI Professional Production Studio

## Scope

Organization Studio is an organization-only production/control surface. It is separate from Personal AI and Business AI.

## Control-plane model

The native Android client stores and edits organization production configuration. The backend persists organization-scoped production state and enforces `capability_organization_studio`.

Collections:
- `organizationProduction`
- `organizationProductionEvents`

Production state includes:
- source registry
- scene registry
- destination registry
- AI director policy
- safety/fallback rules
- organization production training
- health state
- active source/scene
- session status
- foreground-session requirements and lifecycle state
- camera permissions, foreground-service state, and active capture ownership

## Source and camera connectivity model

A source is metadata plus an endpoint/pairing reference. Registration does not mean the source is connected. A source becomes connected only when a real media provider/transport confirms it.

Supported architecture includes:
- Android camera sources
- USB/capture sources where the Android device exposes them
- network/IP cameras through a supported media transport
- Bluetooth camera/accessory connections where the camera/device exposes a supported Bluetooth transport/profile
- another Android phone acting as a live camera source, using an explicitly supported peer transport; Bluetooth may be used for discovery/pairing/control where supported, while the actual video path must use a transport capable of carrying the required live media
- future production gateway sources

The native client must request the required camera, microphone, Bluetooth/nearby-device, and foreground-service permissions at the point they are needed. A foreground request/session must be explicit, user-visible, and tied to an active production/capture session. The app must not claim a live camera feed until the real transport reports a connected/healthy media stream.

Camera/source controls should support:
- discover/pair
- connect/disconnect
- test signal
- health/latency status
- source naming and production role
- preferred/fallback priority
- manual source selection
- AI-approved source selection
- graceful fallback when a source disappears

## Destination model

Destination metadata supports:
- YouTube
- Facebook
- TikTok
- Instagram
- RTMP/RTMPS
- SRT
- professional broadcast outputs

Credentials and stream keys are not stored in the Android application. Real platform OAuth/API access must be supplied through secure provider integrations.

## AI director safety

The director is constrained by organization policy. It should:
- use approved sources/scenes;
- respect operator override;
- avoid switching on low confidence;
- use configured safe/fallback sources;
- preserve the last valid output when configured;
- log production decisions and failures;
- avoid disruptive switching when source confidence or stream health is unstable.

The backend does not claim that a destination is live merely because it is configured.

## Foreground and session reliability

Professional production sessions must have an explicit foreground lifecycle:
1. Request the required runtime permissions.
2. Start a visible foreground production/capture service before long-running capture or streaming work.
3. Maintain the service while an active production session owns camera/microphone/media resources.
4. Release resources and stop the foreground service on safe stop, disconnect, permission revocation, or fatal media failure.
5. Surface reconnect/fallback state instead of silently continuing with a dead source.

The foreground service is a lifecycle/safety mechanism; it does not by itself provide a live media transport.

## Media-plane boundary

Professional live production requires a media plane in addition to the Android control client:

Android control client
→ secure production control plane
→ media production/encoding engine
→ platform/broadcast outputs

Real multi-camera and multi-destination production requires the media engine and transport adapters to be implemented and verified before the UI can claim end-to-end live operation.

## Pricing

No final pricing or feature tier has been hard-coded. Plan limits will be attached later through the existing catalog/entitlement system.
