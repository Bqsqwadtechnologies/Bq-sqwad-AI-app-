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

## Phase 0.4 model rule audit — 2026-11-02 (source review only)
- Worker `providers.js`: `DEFAULT_MODELS.openai = "gpt-6-luna"`; allowlist is only `gpt-6-luna` and `gpt-5.6-luna`; unknown OpenAI text models fall back to `gpt-6-luna`.
- Worker `index.js` `applyChatModelKey`: default/`bq-7-star` → `gpt-6-luna`; `bq-codron` → `gpt-5.6-luna` only.
- Worker `model-registry-native.js`: text-chat/device-commands safe set is `gpt-6-luna` only.
- Worker `ai-model-catalog.ts` seed: BQ-7-Star → `gpt-6-luna`; BQ-Codron → `gpt-5.6-luna`.
- No production Worker default or capability route uses `gpt-5.6-luna` except explicit Codron selection.
- Status: DONE in source, NOT verified by Worker tests or live provider calls. No code change required.

## Prior long-form audit
- The previous full `docs/COMPLETION_AUDIT.md` history (Phases 1–7, Business Inbox paging, monthly feedback, Teams, etc.) is preserved in git at commit `635e28f32ccd98beb6c63826c2249b2312019e7d` and parent history on `main`.
