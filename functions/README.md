# BQ Sqwad AI backend

The native app uses this Firebase Functions 2nd-gen backend as its server-side control plane.

## Provider policy
- Main Chat default: OpenAI.
- Device/phone-command default: Gemini.
- Web Search default: Tavily.
- Admin routes can replace providers, models and fallbacks without an APK update.
- Provider secrets never go to Android.

## Provider credentials
Existing provider secret names:
- GEMINI_API_KEY
- OPENAI_API_KEY
- PAYSTACK_PUBLIC_KEY
- PAYSTACK_SECRET_KEY
- TAVILY_API_KEY

Additional server-only secret:
- BQ_SECRET_ENCRYPTION_KEY

BQ_SECRET_ENCRYPTION_KEY is a 32-byte encryption key represented as 64 hexadecimal characters. It encrypts provider keys stored in Firestore when the Admin Dashboard rotates them.

## Authentication
Firebase Authentication ID tokens are verified server-side. Admin endpoints require the authenticated email to be isaacoshiomole0@gmail.com.

## Device commands
Main Chat remains OpenAI by default. When the app explicitly sends a device-command capability, the backend routes to Gemini by default. Gemini returns a restricted JSON action plan. Android performs the final action and enforces Android permissions and consent.

The backend never receives or executes arbitrary shell commands.

## Web Search
Tavily is the default search provider. Search can be called from any entitled workspace. Search pricing is read from Firestore and is controlled by Admin. Normal search and deep research have separate credit prices, with plan overrides and limits.

## Payments
The app requests a server-generated preview before checkout. The server reads the authoritative plan and amount, then initializes Paystack. The app receives only the checkout URL/access code.

After checkout, the app sends the transaction reference to the server. The server verifies status, amount, currency and ownership before activating an entitlement. Duplicate fulfillment is prevented. Paystack webhooks are also verified with the webhook signature and reconciled.

Auto-renew is separate from checkout and belongs in Payment Settings.

## Deployment
Firebase Functions 2nd gen with Node 22 is used. Firebase Admin supplies authentication and Firestore access. Deployment requires Firebase Authentication, Firestore and Functions.

No API secret is committed to GitHub.
