Confirmed code-level root cause: the Android client treats every HTTP 5xx response as “busy,” so configuration/pricing/provider failures are incorrectly presented as load; the exact production trigger for reference 53749D60 is not verified because live Firestore adminIssues and Cloudflare tail access are unavailable from this GitHub connection.

# Busy error root-cause investigation

## Confirmed from source
- `app/src/main/java/com/bqsqwad/ai/ui/BqIssues.kt` previously mapped any HTTP status >= 500 and generic phrases such as `http 5` to the same busy message. This masks missing price documents, missing provider keys, quota errors, and internal failures.
- `cloudflare-worker/src/index.js` has explicit fail-closed pricing paths: `featurePrice()` throws `price_not_configured` when `adminAdvancedOptions/capability_<feature>.creditCost` is missing or invalid, and the token-pricing paths can return `token_pricing_not_configured` / `provider_usage_unavailable`.
- `enforceRateLimit()` currently uses an in-memory `Map` keyed by UID, so separate route groups share a bucket and state is isolate-local.
- Client diagnostics are written to `adminIssues/clientdiag_<ref>`; that is not evidence that a server-side record exists for the same reference.

## Change made
- Android error mapping now reserves the “High demand”/busy message for explicit `provider_capacity`, and maps known pricing/provider/usage configuration codes to a non-busy “Something went wrong on our side” message. It tries to use a server-supplied `ref`/ `refCode` before generating a phone-side reference.
- This is a client-side honesty fix, not proof that the production backend now returns the structured codes.

## Still needs live verification
1. Look up `adminIssues/clientdiag_53749D60` in the live Firestore project.
2. Run `npx wrangler tail --format pretty` for Worker `bq-sqwad-ai-app` while sending a single `hello`.
3. Record the server response code, failing step, and server-generated reference.
4. Confirm the actual live feature/token price documents and provider secret/quota status before declaring the incident fixed.

Do not claim reference 53749D60 is resolved until the live record/tail confirms the failing step and a real request succeeds.
