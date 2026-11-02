# COMPLETION_AUDIT

## Phase 0.2 model eligibility — 2026-11-02 (source review only)
- Worker already enforces model eligibility on both chat paths before any provider call.
- `cloudflare-worker/src/index.js` imports `canUseModel` and `resolveModelCatalogEntry`.
- `enforceChatModelEligibility` validates catalog status, user-selectable flag, and fixed provider model IDs (`bq-7-star` → `gpt-6-luna`, `bq-codron` → `gpt-5.6-luna`).
- `canUseModel` returns distinct reasons: `model_unavailable`, `purchased_credits_required`, `out_of_credits`.
- `ai()` and `stream()` both call `enforceChatModelEligibility` when `capabilityName === "chat"`.
- Credit reservation uses `purchasedOnly: true` for BQ-Codron so daily free credit is never spent on Codron (`splitCreditBuckets` drains subscription then permanent only).
- BQ-Codron disables provider fallback substitution on the chat path.
- Website `/api/chat` already mirrors the same rule (see website audit).
- Status: DONE in source, NOT verified by Worker tests, live balance QA, or production chat requests. No code change was required for Phase 0.2 on the Worker.

## Phase 0.3 vault, expiry and secrets — 2026-11-02 (source review only)
- `business-vault-native.js` is imported in Worker `index.js` and used via `appendBusinessVaultEntry` on Business AI plan-builder, advanced, and chat/attachment paths.
- Vault pages and `vaultIndex/*` are written only from the Worker with AES-GCM ciphertext, hash chain, and `assertServerOnlyVaultAccess`.
- `redactForVault` strips OTP/BVN/NIN/PIN patterns before provider calls and before vault storage.
- Firestore rules deny all client read/write on `vault/{allPaths=**}` and `vaultIndex/{allPaths=**}`.
- Storage rules deny all client read/write on `vault/**` and `vaultIndex/**`.
- Plan expiry is enforced at Business AI ingress through `requireBusinessPlan` / entitlement active+expiresAt checks inside `withBusinessAiUsage`.
- `businessIngressAllowed` helper exists in `business-vault-native.js`; operational gate is `requireBusinessPlan` (same active/trialing/grace + expiresAt semantics).
- Existing contract coverage in `cloudflare-worker/test/play-rtdn-contract.test.js` asserts vault append is server-only and redacts secrets (tests not run in this session).
- Status: DONE in source, NOT verified by rules deploy, live client-token deny proof, or production Business AI traffic. No new folders or test files were added.

## Prior audit entries
- Earlier Phase 1–7 and monthly-feedback notes remain in git history on `main` prior commits. This file was rewritten in this commit to record the Phase 0.2 / 0.3 verification only; if prior long-form history is required, restore from the previous commit SHA for `docs/COMPLETION_AUDIT.md`.
