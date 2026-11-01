# MODEL_CATALOG_RELEASE_REPORT

**Product:** BQ Sqwad AI  
**Repos:** Bqsqwadtechnologies/Bq-sqwad-AI-app- + bqsqwadai-b3064406  
**Branch:** main (only)

## Files changed (this phase)

### Website
- `src/lib/token-pricing.ts` — removed duplicate `priceConfigurationError`
- `src/lib/theme-store.ts` — default theme White (System)
- `src/lib/ai-model-catalog.ts` — catalog types, seed BQ-7-Star / BQ-Codron, `canUseModel`
- `src/lib/ai-model-catalog.server.ts` — list/seed/save/public projection/audit
- `src/routes/api/public/models.ts` — GET public models endpoint
- `src/components/chat/ModelSelector.tsx` — chip + popover
- `src/lib/onboarding.ts` — onboarding contract
- `src/lib/secret-chat-crypto.ts` — PBKDF2 + AES-GCM client crypto
- `src/components/onboarding/OnboardingSurvey.tsx` — full-screen survey UI
- `docs/cross-platform-sync-contract.md` — catalog, selectedModelKey, file card, onboarding, secret chat

### App + Worker
- `cloudflare-worker/src/ai-model-catalog.ts` — mirrored types + eligibility
- `app/src/main/java/com/bqsqwad/ai/ui/chat/ModelSelector.kt` — chip + bottom sheet
- `app/src/main/java/com/bqsqwad/ai/ui/chat/ThemeOncePicker.kt` — White (System) recommended
- `app/src/main/java/com/bqsqwad/ai/core/ChatApiClient.kt` — getPublicModels, modelKey on send
- `app/src/main/java/com/bqsqwad/ai/core/ApiConfig.kt` — websiteBaseUrl
- `app/src/main/java/com/bqsqwad/ai/core/OnboardingContract.kt`
- `app/src/main/java/com/bqsqwad/ai/core/SecretChatCrypto.kt`

## Checks passed
- Type/contract consistency between website and Worker for model catalog
- Activation gate: model cannot activate without valid prices
- Public projection never exposes provider IDs or prices

## Not yet verified / incomplete
- Full wiring of ModelSelector into ChatWindow composer (import + state + pass modelKey in transport body)
- Full wiring of ModelSelector into BQSqwadApp.kt main chat surface
- Admin OpenAI `/v1/models` table UI overhaul + Refresh from OpenAI
- Per-token field migration at storage layer (UI already presents per-token; storage still legacy *Per1k for Worker compatibility per existing contract)
- Worker `/models/catalog` route registration in index.js
- End-to-end billing-test-vectors run on both repos
- `npm test` / `./gradlew testDebugUnitTest assembleDebug` green confirmation in CI
- Icons as VectorDrawable / SVG assets for star-spark and code-circuit
- Coding hint chip ("try BQ-Codron") once per conversation
- File card message type UI + download/share/edit/regenerate
- Hold-to-dictate state machine polish on app (website dictation already present)
- Three-dot menu full items (delete, project, secret chat, download, rename, share)
- Secret chat folder UI, biometric unlock, auto-lock, FLAG_SECURE
- Onboarding gate in app launch flow and website first-run
- Notification silhouette icon + shared payload polish
- Team logos / white team look / background upload
- Monthly feedback survey + campaigns

## Honest status
Phase 1 foundation (catalog contract, eligibility, public API, selector components, theme default, onboarding + secret crypto modules) is on `main` in both repositories. Remaining integration into the live composer screens, admin pricing table expansion, and Phase 2 UX features still need to be completed and test-verified before claiming full Prompt G + H delivery.
