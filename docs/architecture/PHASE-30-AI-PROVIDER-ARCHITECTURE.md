# Phase 30 — AI Provider Architecture
## Objective
Create a provider-agnostic server contract.
## Provider lifecycle
- Registered.
- Configured.
- Active.
- Disabled.
- Degraded.
- Failed.
## Routing
Feature -> entitlement -> provider policy -> model -> request -> usage/credit ledger -> response.
## Admin
Admin can activate/deactivate providers and configure routing. Provider secrets remain server-side.
## Acceptance
The Android app never assumes one provider is permanently available.