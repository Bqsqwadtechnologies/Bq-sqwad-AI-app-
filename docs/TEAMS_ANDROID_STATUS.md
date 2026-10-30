# Teams on Android — implementation status

Date: 2026-10-30  
Branch: **main** only  
Repo: `Bqsqwadtechnologies/Bq-sqwad-AI-app-`

## How to open Teams in the app

**Top-left corner menu (dash icon) → Teams**

That item already existed in `BQSqwadApp` (`teamsOpen`). It now hosts the shared Teams product:

1. `WorkspaceTeamScreen` (menu entry) → `TeamsHubScreen` (discover / White / create / join)
2. Opening a team → `TeamWorkspaceScreen` (members, projects, free member chat, Ask AI on team credits)

## Done on main

| Piece | Path |
|--------|------|
| Teams hub UI | `app/.../ui/teams/TeamsHubScreen.kt` |
| Team workspace UI | `app/.../ui/teams/TeamWorkspaceScreen.kt` |
| Menu entry wired | `app/.../ui/chat/WorkspaceTeamScreen.kt` (top-left menu) |
| Worker Teams module | `cloudflare-worker/src/teams-sqwad-native.js` |
| Route handler | `cloudflare-worker/src/teams-routes-handler.js` |

## Still required before it works on a phone

1. **Restore full `cloudflare-worker/src/index.js`** (see `cloudflare-worker/RESTORE_INDEX.md`)  
   Current bootstrap only serves Teams routes; other APIs 503 until restored. Wire:

```js
import { handleTeamsSqwadRoutes } from "./teams-routes-handler.js";
// after /native/team/accept-invite:
{
  const teamsResp = await handleTeamsSqwadRoutes(url, request, env, { verifyFirebaseUser, json });
  if (teamsResp) return teamsResp;
}
```

2. **Deploy the Worker**
3. **Build & install** a new APK (Android Studio / CI)

## Product rules

- Member messages never call the model and never charge credits
- Ask AI uses **team** credit pool only
- Zero team balance → clear message; no silent personal debit
- AI prompt includes member roster (names)
