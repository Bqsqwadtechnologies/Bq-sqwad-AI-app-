# Phase 5 — Authentication & Account Security
## Objective
Design authentication as a native client consuming a secure backend contract. Do not copy the old web authentication implementation into Android.
## Required flows
- Sign up.
- Sign in.
- Sign out.
- Session restore.
- Password recovery.
- Account deletion.
- Device/session visibility.
- Token refresh and expiry handling.
- Protected admin entry.
## Migration rule
The existing app's Firebase authentication behavior may be used as a reference for user identity continuity, but Firebase configuration files, service credentials, private keys, and secrets must not be copied into the new repository.
## Security
Tokens stay in platform-secure storage. Server credentials never ship in the APK. Admin identity is verified server-side; the client never grants admin access based on a local email string alone.
## Acceptance
A normal user cannot enter Admin Control Center through client-side navigation tricks.