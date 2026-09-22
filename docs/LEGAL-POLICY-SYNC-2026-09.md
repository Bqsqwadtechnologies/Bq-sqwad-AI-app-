# BQ Sqwad AI — Privacy & Terms Synchronisation Record

Last reviewed: 22 September 2026

## Why this file exists

The Android app now has an in-app Privacy & Legal Center and authenticated account deletion. The public policy pages at bqsqwadai.com must remain the single public legal source and must accurately match the native application.

Google Play requires the privacy policy to be accessible in-app and on the Play listing, and it must disclose the categories of user/device data, purposes, sharing, security, retention/deletion and developer privacy contact. Google Play also requires an in-app account-deletion path for apps that allow account creation.

## Native Android data map

### Account and authentication
- Firebase Authentication: account identity, email, display name when supplied, authentication provider state.
- Google sign-in and passkeys are optional authentication methods.
- Password credentials are handled by Firebase Authentication; the app does not store the user's Firebase password.

### AI content
- User prompts and generated responses can be stored in chat history when the user is authenticated and a conversation is saved.
- User-selected text attachments are read locally and submitted only when the user asks the AI to analyze them.
- User-selected images can be encoded and submitted to the configured vision route.
- Personal memory is opt-in and is stored server-side when enabled.

### Sensitive device capabilities
- Camera: requested only for live camera/vision features or user-selected image capture.
- Microphone: requested only for voice input/live voice.
- Screen capture: uses Android MediaProjection user consent for each capture session.
- Bluetooth permissions are declared for supported connected-device functionality.
- Device settings/device actions are executed only through the supported feature flow and user confirmation where required.
- The app does not declare contacts, SMS, call-log or location permissions in its Android manifest.

### Server-side processing
- Firebase is used for authentication and Firestore-backed application data.
- AI requests are routed through the backend; provider credentials are not placed in the APK.
- Social OAuth tokens are stored server-side when a user/admin connects a supported social account.
- Payment provider credentials remain server-side. Native Android purchase flow uses Google Play Billing; legacy Paystack backend code remains for legacy/website compatibility.

### Security
- Admin provider secrets are encrypted server-side.
- Admin TOTP secrets are encrypted server-side.
- User/admin passkeys use WebAuthn-style credential storage with public keys only.
- Admin biometric enrollment stores a public verification key; private key material remains in the Android Keystore.
- Account deletion removes the authenticated user's operational application data and attempts to remove business-training storage objects.

## Retention and deletion

Users can delete their account from Settings → Delete account and data. The server removes operational account data and deletes the Firebase Authentication account.

Financial, tax, fraud-prevention or security records may need to be retained when required by applicable law. Such retention must be minimized and must not be described as ordinary active account data.

## Public-policy corrections required

The currently published public Privacy Policy was reviewed on 22 September 2026. It contains statements that must be synchronised with the native app before the next release:

1. It describes the Android service together with a PWA; the Android privacy section should clearly identify the native Android application.
2. It says Paystack processes native subscription/credit purchases. Native Android purchases now use Google Play Billing. Legacy Paystack processing should be described separately as legacy/website functionality if it remains active.
3. It describes Google Analytics 4. The native Android dependency set currently does not include a Google Analytics Android SDK; the public policy should only retain this statement if analytics is actually active elsewhere in the service and the scope is clearly distinguished.
4. It lists location/device controls. The native manifest does not declare location permission; location should only be described for services that actually process location elsewhere.
5. It should explicitly describe Firebase Authentication, Firestore, backend AI routing, chat history, memory, attachments, camera, microphone, MediaProjection and passkeys because these are active native capabilities.
6. The Terms page currently describes paid plans as monthly renewal only. It must describe the actual current purchase/catalog model used by each surface, including Google Play Billing for the native Android app.
7. The public policy and Terms should use one canonical version/date and the app consent version must be updated whenever the public legal text materially changes.

## Exact-section navigation

The Android app provides:
- Settings → Privacy & Legal Center.
- Topic-level Privacy Policy links using browser text-fragment navigation.
- Topic-level Terms & Conditions links using browser text-fragment navigation.
- Full Privacy Policy and full Terms links.
- Account deletion directly inside Settings.

## Important deployment note

This repository contains the native app/backend. The public website legal pages are hosted outside this repository. Updating this file does not publish changes to those public pages. The website legal content must therefore be synchronised in the website deployment before claiming that the public policy and native implementation are fully aligned.
