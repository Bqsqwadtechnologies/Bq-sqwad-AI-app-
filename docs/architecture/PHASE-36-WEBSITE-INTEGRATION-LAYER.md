# Phase 36 — Website Integration Layer
## Objective
Keep the website and native app aligned where the product requires shared state while preserving native Android implementation.
## Shared contracts
- Account identity.
- Entitlements.
- Pricing catalog.
- Credits.
- Subscriptions/payment status.
- Privacy policy version.
- Feature configuration.
- Business configuration.
- Referral state.
## Rule
Website remains SSR/SEO/web-native. The Android app does not become a WebView/TWA wrapper.
## Acceptance
A server-side catalog change can reach both surfaces through their supported clients without copying the website UI into Android.