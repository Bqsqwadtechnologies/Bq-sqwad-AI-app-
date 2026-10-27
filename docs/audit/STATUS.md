# BQ Sqwad AI — Audit STATUS

**Branch:** main  
**Last updated:** 2026-10-27  
**Android/Worker commits:** e3046a30 (workflows), 2d624107 (billing-hold), 997bbdbd, 675b81c3 (model-registry floors)

## Phase status

| Phase | Status | Evidence |
|-------|--------|----------|
| 0 Read-only audit | PARTIAL | This file; workflows and billing paths inspected |
| 1 Operations (manual-only workflows) | **CONFIRMED FIXED** | Commit e3046a30 — push triggers removed, single confirmed DEPLOY path |
| 2 Billing hold/settle + floors | **PARTIALLY FIXED** | billing-hold.js + 5/5 tests committed; floors removed in model-registry; chargeCredits hold + settle no-throw patched locally in index.js (needs full push of large file) |
| 3 Checkout | UNVERIFIED | Needs device test |
| 4 Vendor copy | UNVERIFIED | Scan pending |
| 5 Shared sync | UNVERIFIED | |
| 6 Remaining handoff | UNVERIFIED | |
| 7 Chat Folders + Journey | MISSING | Not started |

## Regression checklist (R1–R12)

| ID | Item | Status |
|----|------|--------|
| R1 | Camera preview full-screen | UNVERIFIED |
| R2 | Live voice billing by server time | UNVERIFIED |
| R3 | Live billing not evadable | UNVERIFIED |
| R4 | AI-birthday notices | UNVERIFIED |
| R5 | Chat bubble colours | UNVERIFIED |
| R6 | Generated images as file parts | UNVERIFIED |
| R7 | Guests never see "temporarily unavailable" | UNVERIFIED |
| R8 | Google sign-in error mapping | UNVERIFIED |
| R9 | Connectors truthful status | UNVERIFIED |
| R10 | Admin support server-authorised | UNVERIFIED |
| R11 | Credit ledgers show app + web | UNVERIFIED |
| R12 | Settings sync | UNVERIFIED |

## Workflow trigger table (after Phase 1)

| Workflow | Trigger |
|----------|---------|
| Deploy Cloudflare Worker (manual) | workflow_dispatch only (type DEPLOY) |
| Validate Main | workflow_dispatch only |
| Release APK | workflow_dispatch only |
| Package Identity Audit | workflow_dispatch only |
| Full System Verification | workflow_dispatch only |

## Billing notes

- `computeHold` / `computeSettlement` pure module committed and tested.
- `chargeCredits` (allowFractional) holds only spendable balance (local).
- `settleCreditReservation` no longer throws after delivery (local).
- 1-credit floors removed from model-registry and index capability defaults.
- Remaining for Phase 2: push full index.js changes, `/credits` reserved field, app display formatCredits, vision token path, Admin Truth Table.

## Next

Finish Phase 2 index.js integration → Phase 3 Checkout → Phase 4 Copy → …
