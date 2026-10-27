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

## Notification logo upload location

Upload the full notification logo image to this exact repository path:

`app/src/main/res/drawable-nodpi/bq_notification_logo.png`

**Required filename:** `bq_notification_logo.png`

Use PNG format, transparent background if the logo has one, and keep the full BQ Sqwad AI logo centered with clear padding. Do not overwrite `bq_sqwad_ai_main_app_logo.jpg` or `bq_sqwad_logo`.

Both push notifications and local reminder notifications now look for the optional resource name `bq_notification_logo` and automatically use it as the large notification icon. Until the PNG is uploaded and the app is rebuilt, they fall back to the bundled `bq_sqwad_logo`. Android's small status-bar icon remains `bq_notification_mark`, because Android requires that icon to be a simple monochrome mark; the uploaded full logo is used as the large notification icon.

## Provider-cost billing

All text-chat requests now use provider-usage billing when token rates are configured. The server computes measured input/output/cached-token provider cost, converts it using the configured credit reference value, then applies the configured markup/fee. If model/token pricing is missing, the Worker returns a configuration error rather than silently falling back to one fixed credit per chat message.

Active, unexpired subscriptions for matching capability names are exempt from credit deductions where configured. The verified super administrator remains exempt. Other features continue using their configured billing mode and prices.
