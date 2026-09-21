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

## Source model

A source is metadata plus an endpoint/pairing reference. Registration does not mean the source is connected. A source becomes connected only when a real media provider/transport confirms it.

Supported architecture includes:
- Android camera sources
- USB/capture sources where the Android device exposes them
- network/IP sources through a supported media transport
- future production gateway sources

## Destination model

Destination metadata supports:
- YouTube
- Facebook
- TikTok
- Instagram
- RTMP/RTMPS
- SRT
- broadcast outputs

Credentials and stream keys are not stored in the Android application. Real platform OAuth/API access must be supplied through secure provider integrations.

## AI director safety

The director is constrained by organization policy. It should:
- use approved sources/scenes;
- respect operator override;
- avoid switching on low confidence;
- use configured safe/fallback sources;
- preserve the last valid output when configured;
- log production decisions and failures.

The backend does not claim that a destination is live merely because it is configured.

## Media-plane boundary

Professional live production requires a media plane in addition to the Android control client:

Android control client
→ secure production control plane
→ media production/encoding engine
→ platform/broadcast outputs

The repository currently contains the control-plane foundation. A real production media engine and individual platform credentials are separate integrations and must be implemented before claiming end-to-end multi-platform broadcast.

## Pricing

No final pricing or feature tier has been hard-coded. Plan limits will be attached later through the existing catalog/entitlement system.
