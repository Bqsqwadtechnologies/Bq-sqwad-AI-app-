# Manual deployment and notification logo setup

## Cloudflare Worker deployment

The Worker entry point and deployment configuration are in this folder:
- Entry point: `cloudflare-worker/src/index.js`
- Config: `cloudflare-worker/wrangler.toml`
- Billing implementation: `cloudflare-worker/src/token-billing-native.js`

To deploy manually from a terminal with Wrangler authenticated to the correct Cloudflare account:

1. Open the repository and enter `cloudflare-worker/`.
2. Run `npm install`.
3. Run `npm run check` to perform the Worker JavaScript syntax checks.
4. Run `npm run deploy:manual` to publish Worker `bq-sqwad-ai-app`.

This is a manual deploy only. No GitHub Actions workflow was triggered or added. Keep all existing Cloudflare secrets configured, especially `BQ_FIREBASE_SERVICE_ACCOUNT_JSON`, `FIREBASE_WEB_API_KEY`, `OPENAI_API_KEY`, `GEMINI_API_KEY`, `TAVILY_API_KEY`, and `SUPER_ADMIN_EMAIL`. Never put secret values in GitHub.

## Notification logo upload location

Upload the full notification logo image to this exact repository path:

`app/src/main/res/drawable-nodpi/bq_notification_logo.png`

**Required filename:** `bq_notification_logo.png`

Use PNG format, transparent background if the logo has one, and keep the full BQ Sqwad AI logo centered with clear padding. Do not overwrite `bq_sqwad_ai_main_app_logo.jpg` or `bq_sqwad_logo`.

Both push notifications and local reminder notifications now look for the optional resource name `bq_notification_logo` and automatically use it as the large notification icon. Until the PNG is uploaded and the app is rebuilt, they fall back to the bundled `bq_sqwad_logo`. Android's small status-bar icon remains `bq_notification_mark`, because Android requires that icon to be a simple monochrome mark; the uploaded full logo is used as the large notification icon.

## Provider-cost billing

Text chat now defaults to provider-usage billing when token rates are configured. The server computes measured input/output/cached-token provider cost, converts it using the configured credit reference value (default USD 0.07999 per credit), then charges provider cost × 1.80 (provider cost plus 80%). If model/token pricing is missing, the Worker returns a configuration error rather than silently falling back to one fixed credit per chat message.

Active, unexpired subscriptions for matching TruthGuard, Learning, Companion, and Business capability names are exempt from credit deductions. The verified configured super administrator remains exempt. Other features continue using their configured billing mode and prices.
