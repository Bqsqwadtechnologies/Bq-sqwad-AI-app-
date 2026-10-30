# URGENT — Restore cloudflare-worker/src/index.js

A temporary bootstrap replaced the full Worker entry while wiring Teams routes.
**Deploying the current index.js will break chat, billing, and most native routes** until restored.

## Fastest restore (GitHub web)

1. Open: https://github.com/Bqsqwadtechnologies/Bq-sqwad-AI-app-/blob/ec3d41827a28881769d14008c88fadb8cd079ff9/cloudflare-worker/src/index.js
2. Click **Raw** → save the file
3. Replace `cloudflare-worker/src/index.js` on `main` with that file
4. Add after `team-workspace-native.js` import:

```js
import { handleTeamsSqwadRoutes } from "./teams-routes-handler.js";
```

5. After the `/native/team/accept-invite` handler, add:

```js
{
  const teamsResp = await handleTeamsSqwadRoutes(url, request, env, { verifyFirebaseUser, json });
  if (teamsResp) return teamsResp;
}
```

6. Commit + deploy Worker

## CLI restore

```bash
git checkout ec3d41827a28881769d14008c88fadb8cd079ff9 -- cloudflare-worker/src/index.js
# then add the two snippets above
git add cloudflare-worker/src/index.js
git commit -m "fix: restore full Worker index.js + Teams routes"
git push origin main
```

## Already on main (safe)

- `cloudflare-worker/src/teams-sqwad-native.js`
- `cloudflare-worker/src/teams-routes-handler.js`
- Android `TeamsHubScreen.kt` + `TeamWorkspaceScreen.kt`

## Do not deploy Worker until index.js is restored
