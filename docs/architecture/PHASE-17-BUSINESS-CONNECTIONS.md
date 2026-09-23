# Phase 17 — Business Connections
## Objective
Design secure business channel connections.
## Reference capabilities
The old product includes social, messaging, email, CRM, booking, API, and communication integrations. Exact availability remains catalog/config controlled.
## Security
OAuth/access tokens and provider secrets remain server-side. The Android app receives safe connection status and performs authorized user flows.
## UX
Show connection state, permissions/scopes, last sync, errors, and disconnect controls.
## Acceptance
A provider can be disabled remotely without leaving a misleading active-connect button in the client.