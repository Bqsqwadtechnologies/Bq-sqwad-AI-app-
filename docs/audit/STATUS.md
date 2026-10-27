# BQ Sqwad AI — STATUS

**Branch:** main

## Done on main

| Item | Commit / path |
|------|----------------|
| Manual workflows | e3046a30 |
| billing-hold.js + tests | 2d624107 |
| BrandCopy + guard test | 2eaae4f9 |
| TeamChooser | 8ffb5919 |
| AiReplyActions + activity photo card | 8ffb5919 |
| MainChatHelpers | d23b82e2 |
| **team-workspace-native.js** (invite, roles, charge approval) | af5dd185 |
| **WorkspaceTeamScreen + SettingsCreditsCard** | f2ce8940 |
| Folders/Journey handoff | 209fcabe |
| Terms/Privacy → website | already in app + team screen |

## Must still land from local clone (large files)

1. `cloudflare-worker/src/index.js` — import + `/native/team/*` routes + billing hold call sites
2. `app/.../BQSqwadApp.kt` — hide main-chat credits, compact sources, wire SettingsCreditsCard + Team screens

## Deploy

After index.js is on main: Actions → Deploy Cloudflare Worker (manual) → type **DEPLOY**
