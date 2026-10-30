# Teams on Android — implementation status

Date: 2026-10-30  
Branch: **main** only  
Repo: `Bqsqwadtechnologies/Bq-sqwad-AI-app-`

## Goal

Match the website Teams product (`teams/{teamId}`, unique username, public/private, projects, team credit pool, Ask AI only).

Contract: website `docs/APP_CONTRACT_TEAMS.md`.

## Done on main

### Worker
- New module: `cloudflare-worker/src/teams-sqwad-native.js`
  - create, search, list mine, get by username/id
  - join (public immediate / private pending), leave, list members, approve member
  - list projects, list/post messages
  - **Ask AI** (debits `teamCreditPools` only; member chat free)
  - seed White team helper
  - AI prompt includes member roster

### Legacy (unchanged)
- Old owner-centric workspace remains at `/native/team/*` and `team-workspace-native.js`
- New shared Teams use `/native/teams/*` (plural) to avoid collision

## Still required

1. **Wire routes in `cloudflare-worker/src/index.js`**

Add import:

```js
import {
  teamsCreate, teamsSearch, teamsListMine, teamsGet, teamsJoin, teamsLeave,
  teamsListMembers, teamsApproveMember, teamsListProjects, teamsListMessages,
  teamsPostMessage, teamsAskAi, teamsSeedWhite,
} from "./teams-sqwad-native.js";
```

Add handlers (after existing `/native/team/*` block):

```
POST /native/teams/create
POST /native/teams/search
GET  /native/teams/mine
POST /native/teams/get
POST /native/teams/join
POST /native/teams/leave
POST /native/teams/members
POST /native/teams/approve-member
POST /native/teams/projects
POST /native/teams/messages
POST /native/teams/post-message
POST /native/teams/ask-ai
POST /native/teams/seed-white
```

(A local patch of `index.js` was prepared; the file is ~394 KB so the route block must be applied carefully on main.)

2. **Android UI**
- Teams hub (discover, recommended White, create, join)
- Team workspace `/t/{username}` equivalent (members, projects, chat, Ask AI, team balance)
- Navigation entry in main menu / drawer

3. **Deploy Worker** after route wire-up so the app can call the new endpoints.

## Rules (must stay true)

- Member messages never call the model and never charge credits
- Ask AI / @ai only uses **team** credits
- Zero team balance → clear message; no silent personal debit
- AI addresses members by name from the roster
