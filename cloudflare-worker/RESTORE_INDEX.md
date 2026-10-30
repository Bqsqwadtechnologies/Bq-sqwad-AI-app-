# URGENT — Restore cloudflare-worker/src/index.js

A temporary bootstrap replaced the full Worker entry while wiring Teams routes.

## Restore in one command (GitHub UI or local git)

```bash
git fetch origin
git checkout ec3d41827a28881769d14008c88fadb8cd079ff9 -- cloudflare-worker/src/index.js
```

Then add the Teams handler (2 lines):

1. After the team-workspace-native import:
```js
import { handleTeamsSqwadRoutes } from "./teams-routes-handler.js";
```

2. After the `/native/team/accept-invite` route block:
```js
{
  const teamsResp = await handleTeamsSqwadRoutes(url, request, env, { verifyFirebaseUser, json });
  if (teamsResp) return teamsResp;
}
```

Commit and deploy the Worker.

## Good commits

- Full index before breakage: `ec3d41827a28881769d14008c88fadb8cd079ff9` (blob `404d0cbfdc4dd5a2800eaa7a9872755e7d433e1f`)
- Teams module: `teams-sqwad-native.js`
- Teams route handler: `teams-routes-handler.js`

## Bootstrap currently on main

Only `/native/teams/*` and `/health` respond. All other native routes return 503 until restore.
