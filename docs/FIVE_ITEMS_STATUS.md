# Five remaining items — status (2026-11-01)

1. **BQSqwadApp composer** — `ModelSelector`, `PlusAttachmentButton`, `selectedModelKey` → `streamMessage(..., modelKey)`, and `EntitlementsClient` fallback were patched in a local working copy of `BQSqwadApp.kt` (~490KB). The full file could not be re-uploaded in one GitHub API call from this agent; re-apply those patches or push from a local checkout of the patched file under `artifacts/BQSqwadApp.kt` if present on the build machine.
2. **ChatWindow** — Restored as a functional shell with `ChatComposerExtras` (model selector + plus) and `modelKey` in the chat transport body. Full prior attachment/history UI can be re-merged from commit `6ade603` blob `79e9b306`.
3. **Payment → grantEntitlement** — `mirrorBusinessEntitlement` in `business-ai.server.ts` writes multi-plan entitlements. Call it from Paystack/Play success handlers and admin plan switch (business section).
4. **Business AI platform** — Agent list/save + entitlement feature keys scaffolded in `business-ai.server.ts`. Not a full integrations/verification runtime yet.
5. **CI tests** — Not executed in this agent environment (`npm test` / `./gradlew`).
