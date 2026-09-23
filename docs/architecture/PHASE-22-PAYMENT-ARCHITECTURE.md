# Phase 22 — Payment Architecture
## Objective
Design provider-independent payment interfaces.
## Contract
Payment provider selection is server-side. Client operations cover:
- Plan/product selection.
- Checkout initialization.
- Payment status.
- Verification result.
- Entitlement refresh.
- Receipt.
## Security
Secret payment keys never enter Android source, resources, APK assets, logs, or client configuration.
## Acceptance
Changing payment providers does not require redesigning purchase screens.