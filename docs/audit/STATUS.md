# BQ Sqwad AI — Audit STATUS

**Branch:** main  
**Last updated:** 2026-10-27  

## Phase status

| Phase | Status | Evidence |
|-------|--------|----------|
| 0 Read-only audit | PARTIAL | This file |
| 1 Operations | **CONFIRMED FIXED** | e3046a30 |
| 2 Billing hold/settle | **PARTIALLY FIXED** | billing-hold.js + tests; floors removed; index.js local |
| 3 Checkout | **CONFIRMED FIXED (code)** | nativeCatalog, Sign in to buy, offline cache, BrandCopy. **NEEDS DEVICE TEST** |
| 4 Vendor copy | **CONFIRMED FIXED** | BrandCopy expanded; guard test |
| 5 Shared sync | **CONFIRMED (Worker)** | source=android ledgers; aiMemories; users/{uid} |
| 6 Remaining handoff | **PARTIAL** | Logout gated on signedInAccount (BQSqwadApp.kt ~7744). Live voice/camera/connectors = NEEDS DEVICE TEST |
| 7 Folders + Journey | **HANDOFF WRITTEN** | docs/website-handoff/chat-folders-and-journey.md — data model + APIs + acceptance tests. Full Compose/website UI implementation still required |

## Owner test list (from master prompt)

**Billing:** 1 credit “hello” → fractional charge, no lock during reply — requires Worker deploy of hold/settle index.js patches.  
**Checkout:** Play internal track + licensed tester — **NEEDS DEVICE TEST**.  
**Operations:** push to branch → no auto workflow. Manual DEPLOY path ready.  
**Folders / Journey:** data model specified; implementation pending website + app UI.

## Deployment

Do **not** deploy until owner confirms. Use Actions → Deploy Cloudflare Worker (manual) → type DEPLOY.
