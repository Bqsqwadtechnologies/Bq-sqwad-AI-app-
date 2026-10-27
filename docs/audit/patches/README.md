# Local patches to apply (git-authenticated machine)

Diff is small (~230 lines app, ~80 lines worker) even though full files are large.

## What the local working tree already contains

### `BQSqwadApp.kt`
- `BqChatHeader` wired (New chat, dash menu, title swipe)
- Signed-out gate: SignIn/SignUp only until Firebase user exists
- Settings: `SettingsCreditsCard` + Team & workspace under Payments
- Image generation: logo-only placeholder (no progress copy)
- Reply action row hidden while streaming / generating

### `cloudflare-worker/src/index.js`
- billing-hold, team-workspace-native, training-pipeline-native imports
- `/native/team/*` and `/admin/training-candidates*` routes
- hold/settle call sites for fractional token billing

## Apply from this agent workspace or your clone

```bash
cd Bq-sqwad-AI-app-
git add app/src/main/java/com/bqsqwad/ai/ui/BQSqwadApp.kt cloudflare-worker/src/index.js cloudflare-worker/package.json
git commit -m "wire: chat header redesign, auth gate, Settings credits, team+training routes"
git push origin main
# Actions → Deploy Cloudflare Worker (manual) → type DEPLOY
```

Modules already on main do not need re-push:
`BqChatHeader`, `BqImageExperience`, `ThemeOncePicker`, `WorkspaceTeamScreen`, `MainChatHelpers`, `billing-hold`, `team-workspace-native`, `training-pipeline-native`.
