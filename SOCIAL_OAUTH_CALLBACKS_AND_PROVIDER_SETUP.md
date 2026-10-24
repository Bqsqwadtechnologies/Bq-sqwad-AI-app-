# BQ Sqwad AI — Verified Social OAuth Callback & Provider App Setup

## Canonical identities

- App: BQ Sqwad AI
- Company: BQ Sqwad Technologies
- Android package: `com.bqsqwad.ai`
- Firebase project: `bqsqwadai`
- Website origin: `https://bqsqwadai.com`

## Verified callback from the main branch

The current app backend is the Cloudflare Worker `bq-sqwad-ai-app`, whose `cloudflare-worker/src/index.js` exposes `GET /social/oauth/callback`.

The canonical callback for social OAuth supported by the current Worker is:

**https://bq-sqwad-ai-app.isaacoshiomole0.workers.dev/social/oauth/callback**

The Worker stores the exact `redirectUri` used for each authorization and exchanges the returned code using that same URI. Register this exact URI in each supported provider's developer console. Do not use the retired Firebase Functions callback for this Worker flow. The Worker currently implements X, YouTube and TikTok OAuth; Facebook and Instagram are not enabled by this Worker implementation and must not be presented as working connectors.

## Provider matrix

| Provider | Developer app type | OAuth/API product | Callback to register | Main credentials |
|---|---|---|---|---|
| Facebook Pages | Meta app | Not implemented in the current Worker | Not applicable until implemented | Meta App ID + App Secret (do not configure for this Worker yet) |
| Instagram | Meta app | Not implemented in the current Worker | Not applicable until implemented | Meta App ID + App Secret (do not configure for this Worker yet) |
| YouTube | Google Cloud **Web application** OAuth client | YouTube Data API v3 | `https://bq-sqwad-ai-app.isaacoshiomole0.workers.dev/social/oauth/callback` | OAuth Client ID + Client Secret |
| TikTok | TikTok developer app | Login Kit | `https://bq-sqwad-ai-app.isaacoshiomole0.workers.dev/social/oauth/callback` | Client key/ID + Client Secret |
| X | X developer app | OAuth 2.0 | `https://bq-sqwad-ai-app.isaacoshiomole0.workers.dev/social/oauth/callback` | Client ID + Client Secret |
| WhatsApp | Meta app/business setup | WhatsApp Business Platform | Not this OAuth callback; webhook/business configuration is required | Meta business credentials/tokens |
| Telegram | Telegram Bot | Bot API | Not OAuth; webhook is required | Bot token |
| SMS | Messaging provider | Provider API/webhooks | Not OAuth; provider webhook is required | Provider credentials |
| Website widget | BQ Sqwad website/business channel | BQ Sqwad backend | Not OAuth | No provider OAuth client |
| CRM | Provider-specific | Provider OAuth/API | Provider-specific; must be implemented before registration | Provider-specific |

## Important Firebase Authentication exception

For Firebase Authentication with Facebook Login, the Firebase Auth callback is:

`https://bqsqwadai.firebaseapp.com/__/auth/handler`

This is separate from the BQ Sqwad Business social-channel OAuth callback above.

Do not replace the Firebase Auth callback with the Business social OAuth callback.

## Verified repository platforms

The current Cloudflare Worker implements OAuth support for:

- youtube
- tiktok
- x

The Worker reports only platforms whose required client-ID and client-secret bindings are present. This status means credentials are configured; it does not guarantee that the provider will approve the app or that a connection has been tested.

## YouTube recreation

Because the old YouTube Web OAuth client was deleted, recreate it as:

- Google Cloud project: the project associated with `bqsqwadai`
- OAuth client type: **Web application**
- Name: `BQ Sqwad AI — YouTube`
- Authorized redirect URI:
  `https://bq-sqwad-ai-app.isaacoshiomole0.workers.dev/social/oauth/callback`
- API: YouTube Data API v3
- Store the Client ID in the BQ Sqwad admin/provider configuration.
- Store the Client Secret server-side only.

The Worker’s YouTube scopes currently request:

- `https://www.googleapis.com/auth/youtube.readonly`
- `https://www.googleapis.com/auth/youtube.upload`

## Meta

For Business social connections:

- Meta App ID is configured for Facebook/Instagram.
- Meta App Secret is stored server-side.
- Business social callback:
  `https://bq-sqwad-ai-app.isaacoshiomole0.workers.dev/social/oauth/callback`

For Firebase Facebook Authentication:

- Firebase Auth callback:
  `https://bqsqwadai.firebaseapp.com/__/auth/handler`

These are two different flows.

## TikTok

Use a TikTok developer app with Login Kit and register:

`https://bq-sqwad-ai-app.isaacoshiomole0.workers.dev/social/oauth/callback`

## X

Use an X developer app with OAuth 2.0 and register:

`https://bq-sqwad-ai-app.isaacoshiomole0.workers.dev/social/oauth/callback`

The repository generates PKCE values for X.

## Security

Never commit:

- Meta App Secret
- YouTube Client Secret
- TikTok Client Secret
- X Client Secret
- OAuth access tokens
- OAuth refresh tokens
- API secrets

The Android app must not contain these secrets.

## Manual registration order

1. Firebase/Google Android configuration
2. Meta Facebook/Instagram
3. Recreate YouTube Web OAuth client
4. TikTok Login Kit
5. X OAuth 2.0
6. WhatsApp Business separately
7. Telegram Bot separately
8. SMS provider separately
9. Website widget
10. Provider-specific CRM integrations

## Verification rule

Before declaring an integration complete, verify all three:

1. Provider redirect/callback exactly matches the value above when OAuth is used.
2. Provider Client ID is configured in BQ Sqwad admin configuration.
3. Provider Client Secret is stored in the server-side secret store.

No fake callback URLs or placeholder credentials should be used.
