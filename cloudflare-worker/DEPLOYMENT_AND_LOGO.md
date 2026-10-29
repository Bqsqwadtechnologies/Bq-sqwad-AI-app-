# Manual deployment and notification logo setup

## Cloudflare Worker deployment (manual only)

**Active config:** `cloudflare-worker/wrangler.jsonc`  
(contains cron trigger `0 3 * * *`, observability, compatibility_date 2026-09-27, ALLOWED_ORIGINS)

**Legacy config (do not use for deploy):** `cloudflare-worker/wrangler.toml`  
(compatibility_date 2026-10-17, no cron/observability — kept only for reference; mark as legacy)

Entry point: `cloudflare-worker/src/index.js`

### GitHub Actions (recommended)

1. Go to Actions → **Deploy Cloudflare Worker (manual)**
2. Click **Run workflow**
3. Type exactly `DEPLOY` in the confirm field
4. The workflow runs `npm run check` + `npm test`, then deploys only if the confirm matches
5. Build id (`BQ_BUILD_ID` = first 12 chars of the commit SHA) is injected as a Worker var

### Local terminal (alternative)

1. `cd cloudflare-worker`
2. `npm install`
3. `npm run check`
4. `npm test`
5. `npm run deploy:manual`  (uses `wrangler.jsonc`)

Keep all existing Cloudflare secrets configured (`BQ_FIREBASE_SERVICE_ACCOUNT_JSON`, `FIREBASE_WEB_API_KEY`, provider keys, etc.). Never put secret values in GitHub or commits.

No workflow runs or deploys automatically on push to `main` any more.

## Android launcher and notification artwork

The supplied artwork is staged at `ICON_UPLOAD/bq-icon-white-background.png`. Its bytes are JPEG-encoded despite the source filename's `.png` suffix, so the Android drawable resources use the correct `.jpg` extension:

- Launcher icon resource: `app/src/main/res/drawable-nodpi/bq_launcher_logo.jpg` (manifest resource name `@drawable/bq_launcher_logo`).
- Full notification artwork: `app/src/main/res/drawable-nodpi/bq_notification_logo.jpg` (resource name `R.drawable.bq_notification_logo`).
- In-app branding remains `app/src/main/res/drawable-nodpi/bq_sqwad_logo.jpg` and is intentionally unchanged.

The notification service uses the uploaded artwork for the expanded notification picture and derives a monochrome small icon from it for notifications it renders. The system default FCM status-bar icon remains `bq_notification_mark`, because Android requires a simple monochrome mask for that slot.

## Provider-cost billing

All text-chat requests now use provider-usage billing when token rates are configured. The server computes measured input/output/cached-token provider cost, converts it using the configured credit reference value, then applies the configured markup/fee. If model/token pricing is missing, the Worker returns a configuration error rather than silently falling back to one fixed credit per chat message.

Active, unexpired subscriptions for matching capability names are exempt from credit deductions where configured. The verified super administrator remains exempt. Other features continue using their configured billing mode and prices.
