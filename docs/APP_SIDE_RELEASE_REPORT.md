# APP_SIDE_RELEASE_REPORT (Prompt J + K foundation)

## Files added (main)
- `cloudflare-worker/src/orchestrator-native.js`
- `cloudflare-worker/src/presence-usage-native.js`
- `cloudflare-worker/src/free-credit-notify-native.js`
- `cloudflare-worker/src/business-vault-native.js`
- `cloudflare-worker/src/composio-tools-native.js`
- `cloudflare-worker/test/orchestrator-vault-composio.test.js`
- `app/.../PresenceClient.kt`
- `app/.../BusinessStorageMeter.kt` (+ expiry banner)
- `app/.../PlanToolsConnectScreen.kt`

## Behavior
- Primary OpenAI → optional Gemini fallback with cost guard
- Presence + usageDaily helpers
- Daily free credit notification document + FCM
- Vault redaction, ingress expiry gate, storage meter rules
- Composio plan tools (catalog-driven; not forced BVN/NIN)

## Tests
```
node --test cloudflare-worker/test/orchestrator-vault-composio.test.js
```
(run in CI / local worker tree)

## Wire-in required in large index.js / BQSqwadApp
- Import orchestrator in stream path
- Route POST /presence/ping
- Call notifyDailyFreeCredit after grant
- COMPOSIO_API_KEY Worker secret

## Unverified
- Full index.js integration (file too large for single API rewrite)
- Gradle assemble in this agent
- Live Composio OAuth round-trip
