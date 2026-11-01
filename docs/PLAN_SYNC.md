# PLAN_SYNC

Plans sync through Firebase entitlements shared by website and Android.

- Read: `GET /api/native/entitlements`
- Write: `grantEntitlement` / `revokeEntitlement` (Paystack, Play, admin)
- App: `EntitlementsClient` + `WorkspaceAccessGate`
- Website: admin plan switch already grants multi-plan rows

Plan-covered modules should not deduct credits when the entitlement feature covers the action (enforced server-side per capability).
