# Cloudflare Worker backend migration

The Cloudflare Worker is now the compatibility gateway and contains a native Cloudflare implementation for the core authenticated AI endpoints:

- `GET /health`
- `POST /ai`
- `POST /ai/stream`
- `POST /ai/vision`

The remaining API surface is intentionally still proxied to the existing Firebase Functions backend until each subsystem is migrated and verified.

## Required Cloudflare secrets

Set these as Worker secrets. Never commit them to GitHub and never paste them into chat.

1. `BQ_FIREBASE_SERVICE_ACCOUNT_JSON`
   - Full JSON of the Firebase/Google service account used for server-side Firestore access.
   - It must have permission to read/write the BQ Sqwad AI Firestore database.
2. `FIREBASE_WEB_API_KEY`
   - The Firebase Web API key for project `bqsqwadai`.
   - This is used only to validate Firebase ID tokens through Firebase Authentication.
3. `OPENAI_API_KEY`
   - The server-side OpenAI API key.

Later migration phases will add provider secrets for Gemini, Tavily, Google Play, Meta, YouTube, TikTok and X as needed.

## User-only setup

These steps require access to the Cloudflare dashboard and Google/Firebase credentials, so they cannot safely be performed from this repository connection:

1. Open the Cloudflare Worker `bq-sqwad-ai-app`.
2. Open **Settings -> Variables and Secrets**.
3. Add the three secrets above as encrypted secrets.
4. Save/deploy the Worker from the Cloudflare dashboard.
5. Do not remove Firebase Functions, Firestore, Auth, or existing service-account permissions yet.

## Migration rule

Firebase remains the data/auth source during the migration. Each subsystem is migrated to Cloudflare first, tested against the existing data model, and only then is the corresponding Firebase Function considered removable.

No GitHub Actions workflow is being created or intentionally triggered by this migration.


### Worker environment variables
- `SUPER_ADMIN_EMAIL`: owner/admin email used by the Worker for super-admin authorization. Keep the value in Cloudflare Worker environment configuration; never commit it as a secret or hardcoded value.


### Required Worker secrets / variables
Configure these in Cloudflare, never in source control:
- `BQ_FIREBASE_SERVICE_ACCOUNT_JSON`
- `FIREBASE_WEB_API_KEY`
- `OPENAI_API_KEY`
- `GEMINI_API_KEY`
- `TAVILY_API_KEY`
- `SUPER_ADMIN_EMAIL`
- `ALLOWED_ORIGINS`
- Social OAuth secrets used by `social.js`
- Google Play verification secrets used by the canonical Play verification path
