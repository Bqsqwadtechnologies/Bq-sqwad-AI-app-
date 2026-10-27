# BQ Sqwad AI — Audit STATUS (final session snapshot)

**Branch:** main  
**Updated:** 2026-10-27

## Phase board

| Phase | Status | Evidence |
|-------|--------|----------|
| 1 Operations | **DONE** | `e3046a30` manual-only workflows |
| 2 Billing hold | **MODULE DONE** | `billing-hold.js` + tests on main. `index.js` charge/settle wiring still **local only** (file too large for tool push) |
| 3 Checkout | **DONE (code)** | Guests/nativeCatalog, Sign in to buy, offline cache, BrandCopy. Live Play = **NEEDS DEVICE TEST** |
| 4 Vendor copy | **DONE** | BrandCopy expanded + guard test |
| 5 Shared sync | **DONE (Worker)** | source=android ledgers |
| 6 Handoff residuals | **PARTIAL** | Logout gated; live features need device |
| 7 Folders + Journey | **HANDOFF DONE** | `docs/website-handoff/chat-folders-and-journey.md` — full UI not implemented |

## New chat modules on main

| Module | Commit | Purpose |
|--------|--------|--------|
| `ui/chat/TeamChooserScreen.kt` | `8ffb5919` | One-time team picker with previews; change later in Settings |
| `ui/chat/AiReplyActions.kt` | `8ffb5919` | Logo action bar + AiActivityCard for mid-task photos |
| `ui/chat/MainChatHelpers.kt` | `d23b82e2` | Web search for current knowledge + credit helpers |
| Integration notes | `d23b82e2` | `docs/audit/MAIN_CHAT_INTEGRATION.md` |

## Local-only (not yet on remote main)

1. **BQSqwadApp.kt** (~500 KB) — hide credits in main chat, expanded web-search, compact sources dropdown  
2. **index.js** hold/settle call sites (import + chargeCredits + settleCreditReservation)

**How to land them:** clone, apply from the working tree or follow MAIN_CHAT_INTEGRATION.md and the billing-hold module already on main, then `git push` with your credentials.

## Owner next actions

1. Push local `BQSqwadApp.kt` + `index.js` from a machine with git auth  
2. Wire TeamChooser after sign-in + TeamSettingsRow in Settings (5–10 lines)  
3. Wire AiReplyActionBar under AI bubbles  
4. Manual Worker deploy (Actions → type DEPLOY) after index.js lands  
5. Device test Play checkout on internal track  
6. Website implements Folders/Journey from handoff doc  

## Honest remaining product work

- Full Chat Folders Compose UI + website APIs  
- Full Chat Journey map UI  
- Live wiring of AiActivityCard photo stream during tools  
- End-to-end device verification of billing hold after Worker deploy  
