# Dual-repo policy (enforced)

| Product surface | GitHub repo | Branch |
|-----------------|-------------|--------|
| **Android app + Cloudflare Worker** | `Bqsqwadtechnologies/Bq-sqwad-AI-app-` | **`main` only** |
| **Website (Lovable)** | `Bqsqwadtechnologies/Bq-sqwad-AI-` | **`main` only** |

Never put website UI into the app repo. Never put Android Kotlin into the website repo.

Shared backend authority: Firebase `users/{uid}` + Cloudflare Worker API.

## App main — recent

- `BqGeneratingIndicator` = logo-only (no progress text)
- Worker `index.js` restored with billing-hold, team routes, training routes
- Chat modules under `app/.../ui/chat/`

## Website main — generation from main chat

Website `src/routes/api/chat.ts` already exposes a `generateImage` tool for main chat.
Image/PDF/file parity with the Android app uses the same Worker generation endpoints where configured.

## Deploy

- App Worker: Actions → Deploy Cloudflare Worker (manual) → `DEPLOY`
- Website: Lovable publish / usual site deploy from `Bq-sqwad-AI-` `main`
