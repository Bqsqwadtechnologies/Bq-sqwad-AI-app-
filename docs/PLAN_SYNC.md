# PLAN_SYNC

Plans sync through Firebase's canonical `entitlements/{uid}/plans/{planId}` collection, shared by the website and native Android app.

- Website reads: `GET /api/native/entitlements` (all active plans).
- Android catalog reads: Worker `GET /entitlements`, including the canonical multi-plan subcollection.
- Android workspace gate refreshes the website entitlement endpoint before showing a plan-required catalog action.
- Verified Play writes include the canonical plan module, feature list and expiry; legacy configured Play mappings fall back to the canonical commercial catalog.
- Website Paystack recurring and legacy premium checkout fulfilment write canonical entitlement rows using native catalog plan IDs.
- Website entitlement resolution reads every active Google Play plan, not only the single legacy root plan.
- Active product entitlement opens its workspace immediately; tier-specific tools and quotas remain server-authoritative.
- Locked feature actions in the app and website use the label **View catalog** to open the relevant plan catalog.

Provider checkout SKUs, Google Play product/base-plan IDs, package ID, subscription prices, user balances and billing history are unchanged.

Plan-covered usage must still be enforced server-side: an entitlement only skips credit deduction where the active plan explicitly includes the action.

## Runtime verification still required

- Real website Paystack purchase → Android plan status and workspace access.
- Real Google Play purchase → website plan status and workspace access.
- Expiry/cancellation refresh and a signed Android build installed on a device.

No workflow was dispatched and no real payment or manual entitlement grant was performed for this change.
