# Teams on Android — implementation status

Date: 2026-10-30  
Branch: **main** only  
Repo: `Bqsqwadtechnologies/Bq-sqwad-AI-app-`

## Goal

Match the website Teams product (`teams/{teamId}`, unique username, public/private, projects, team credit pool, Ask AI only).

Contract: website `docs/APP_CONTRACT_TEAMS.md`.

## Done on main (this session)

### Worker
- `cloudflare-worker/src/teams-sqwad-native.js`
  - create, search, list mine, get by username/id
  - join (public immediate / private pending), leave, list members, approve member
  - list projects, list/post messages
  - **Ask AI** (debits `teamCreditPools` only; member chat free)
  - seed White team helper
  - AI prompt includes member roster

### Android UI
- `app/src/main/java/com/bqsqwad/ai/ui/teams/TeamsHubScreen.kt`
  - Discover, recommended White, create, join
- `app/src/main/java/com/bqsqwad/ai/ui/teams/TeamWorkspaceScreen.kt`
  - Members, projects, free member chat, Ask AI, team balance

### Legacy (unchanged)
- Old owner-centric workspace remains at `/native/team/*`
- New shared Teams use `/native/teams/*` (plural)

## Still required before you see it in the app

1. **Wire routes in `cloudflare-worker/src/index.js`** (file is ~394 KB)

```js
import {
  teamsCreate, teamsSearch, teamsListMine, teamsGet, teamsJoin, teamsLeave,
  teamsListMembers, teamsApproveMember, teamsListProjects, teamsListMessages,
  teamsPostMessage, teamsAskAi, teamsSeedWhite,
} from "./teams-sqwad-native.js";
```

Handlers after existing `/native/team/*` block:

| Method | Path |
|--------|------|
| POST | `/native/teams/create` |
| POST | `/native/teams/search` |
| GET | `/native/teams/mine` |
| POST | `/native/teams/get` |
| POST | `/native/teams/join` |
| POST | `/native/teams/leave` |
| POST | `/native/teams/members` |
| POST | `/native/teams/approve-member` |
| POST | `/native/teams/projects` |
| POST | `/native/teams/messages` |
| POST | `/native/teams/post-message` |
| POST | `/native/teams/ask-ai` |
| POST | `/native/teams/seed-white` |

2. **Navigation** — add a "Teams" entry in the app drawer / main menu that opens `TeamsHubScreen`, and navigate to `TeamWorkspaceScreen(username)` when a team is opened.

3. **Deploy Worker** so the new endpoints are live.

4. **Build & install** a new APK / run from Android Studio.

## Rules (must stay true)

- Member messages never call the model and never charge credits
- Ask AI / @ai only uses **team** credits
- Zero team balance → clear message; no silent personal debit
- AI addresses members by name from the roster
