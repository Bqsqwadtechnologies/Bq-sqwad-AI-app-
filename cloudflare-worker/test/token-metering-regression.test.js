import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const source = await readFile(new URL("../src/index.js", import.meta.url), "utf8");
const providerSource = await readFile(new URL("../src/providers.js", import.meta.url), "utf8");
const tokenBillingSource = await readFile(new URL("../src/token-billing-native.js", import.meta.url), "utf8");
const teamSource = await readFile(new URL("../src/teams-sqwad-native.js", import.meta.url), "utf8");

test("chat and attachment capability defaults are token-metered and zero prices stay free", () => {
  assert.match(source, /const TOKEN_METERED = new Set\(\["chat", "vision", "chat_attachment", "personal_intelligence", "team_chat", "live_camera", "live_voice", "web_search", "image_generation", "image_edit"\]\)/);
  assert.match(source, /creditCost: TOKEN_METERED\.has\(name\)\s*\? 0/);
  assert.match(source, /const requestedCharge = Number\(amount\)/);
  assert.match(source, /if \(!charge\) return \{ reservationId: null, amount: 0, free: true \}/);
  assert.doesNotMatch(source, /if \(!charge && !options\.allowFractional/);
});


test("team image generation uses the same GPT Image token rule and settles the team pool by actual usage", () => {
  assert.match(teamSource, /resolveTokenPricing\("openai", "gpt-image-2\.5-sunburst", "image_generation"/);
  assert.match(teamSource, /imageTokenPricing/);
  assert.match(teamSource, /async \(uid, reservationId, finalCredits\)/);
  assert.match(teamSource, /debitTeamPoolForMember\(team, member, uid, difference\)/);
  assert.match(teamSource, /refundTeamPoolForMember\(team, member, uid, -difference\)/);
  assert.doesNotMatch(teamSource, /0\.292|0\.35|0\.642|0\.875|3\.033/);
});

test("deep research is fixed-price and its seven-credit rule is applied before Worker settlement", () => {
  assert.doesNotMatch(source, /const TOKEN_METERED = new Set\(\[[^\]]*"deep_research"/s);
  assert.doesNotMatch(source, /ensureDeepResearchPrice|bq_deep_research_fixed_7/);
  assert.match(source, /\["deep_research",\s*"web\.research",\s*7,\s*0,\s*"none"\]/);
  assert.match(source, /const charge = deepResearch\s*\?\s*searchRule\.credits \+ \(searchRule\.unit === "none" \? 0 : searchRule\.creditsPerUnit\)/);
});

test("image editing uses shared image generation quality-tier rules", () => {
  assert.match(source, /if \(key === "image\.generate" \|\| key === "image\.edit"\) await ensureImageGenerationTierDefaults\(\)/);
  assert.match(source, /imageGenerationCreditPrice\(body\.size, body\.quality, control\.modelId\)/);
});

test("native token-metered features use canonical per-token customer credit rates", () => {
  assert.match(tokenBillingSource, /customerCreditsPerToken\(tokenRule, "inputCreditsPerToken", "inputCreditsPer1k"\)/);
  assert.match(tokenBillingSource, /customerCreditsPerToken\(tokenRule, "cachedInputCreditsPerToken", "cachedInputCreditsPer1k"\)/);
  assert.match(tokenBillingSource, /customerCreditsPerToken\(tokenRule, "outputCreditsPerToken", "outputCreditsPer1k"\)/);
  assert.match(tokenBillingSource, /if \(isTokenMeteredFeature && !hasCustomerCreditRule\)/);
  assert.match(tokenBillingSource, /token_pricing_not_configured/);
  assert.match(tokenBillingSource, /creditsPerCachedInputToken: customerCachedCreditsPerToken/);
  assert.match(tokenBillingSource, /const direct = rule\?\.\[tokenField\]/);
});

test("vision reserves an estimate, settles reported token usage, and reverses failed requests", () => {
  const start = source.indexOf("async function vision(request, user, body) {");
  const end = source.indexOf("\nasync function ", start + 10);
  assert.ok(start >= 0 && end > start, "vision function must exist");
  const vision = source.slice(start, end);
  assert.match(vision, /estimateTokenReservation\(/);
  assert.match(vision, /normalizeProviderUsage\(result\.providerId, result\.usage \|\| \{\}\)/);
  assert.match(vision, /calculateTokenCost\(/);
  assert.match(vision, /settleCreditReservation\(/);
  assert.match(vision, /await reverseCredits\(user\.uid, reservation\.reservationId\)/);
  assert.doesNotMatch(vision, /chargeCredits\(user\.uid, control\.creditCost \|\| 0/);
});

test("metered chat cannot use a flat creditCost reservation argument", () => {
  for (const [startMarker, endMarker] of [
    ["async function ai(request, user, body)", "\nasync function vision("],
    ["async function stream(request, user, body)", "\nasync function "],
    ["async function vision(request, user, body)", "\nasync function stream("],
  ]) {
    const start = source.indexOf(startMarker);
    const end = source.indexOf(endMarker, start + startMarker.length);
    assert.ok(start >= 0 && end > start, "metered handler boundaries must exist");
    const handler = source.slice(start, end);
    assert.doesNotMatch(handler, /chargeCredits\(user\.uid,\s*control\.creditCost\s*\|\|\s*0/);
  }
});

test("plain chat settles with provider-reported token formula, not a hard-coded one-credit charge", () => {
  const start = source.indexOf("async function ai(request, user, body)");
  const end = source.indexOf("\nasync function vision(", start + 10);
  const ai = source.slice(start, end);
  assert.match(ai, /const calculation = calculateTokenCost\(/);
  assert.match(ai, /settleCreditReservation\(user\.uid, reservation\.reservationId, calculation\.customerCredits/);
  assert.doesNotMatch(ai, /settleCreditReservation\(user\.uid, reservation\.reservationId,\s*1\s*,/);
});

test("fixed-price shared-charge helper rejects token-metered capabilities", () => {
  const start = source.indexOf("async function withSharedCreditCharge(");
  const end = source.indexOf("\nexport function isPublicCatalogPlan", start);
  assert.ok(start >= 0 && end > start);
  const helper = source.slice(start, end);
  assert.match(helper, /TOKEN_METERED\.has\(String\(capabilityName \|\| ""\)\)/);
  assert.match(helper, /token_metering_route_required/);
});

test("Tavily search and deep research use the shared direct generation credit rules", () => {
  assert.match(source, /activeGenerationRule\(deepResearch \? "web\.research" : "web\.search"\)/);
  assert.match(source, /const charge = deepResearch\s+\? searchRule\.credits/);
  assert.doesNotMatch(source, /const configuredSearchCharge = deepResearch/);
  assert.match(source, /activeGenerationRule\(isCameraMode \? "live.camera" : "live.voice"\)/);
  assert.match(source, /activeGenerationRule\("speech.transcribe"\)/);
  assert.doesNotMatch(source, /\/admin\/ai-billing\/config/);
});

test("website-owned token budgets cap native and connector-aware chat spend", () => {
  assert.match(source, /conversationUsedConnectors = false, maxRequestTokens = 8_000, maxOutputTokens = 512/);
  assert.match(source, /for \(let round = 0; round < 3; round\+\+\)/);
  assert.match(source, /max_output_tokens: outputTokenBudget/);
  assert.match(source, /const outputTokenBudget = Math\.min\(1_024/);
  assert.match(source, /toolDefs\.length >= 12/);
  assert.match(source, /callIndex >= 3/);
  assert.match(source, /tokenBilling \? tokenPricing\.maxRequestTokens : undefined/);
  assert.match(source, /tokenBilling \? tokenPricing\.maxOutputTokens : undefined/);
  assert.match(providerSource, /const DEFAULT_MAX_REQUEST_TOKENS = 8_000/);
  assert.match(providerSource, /const DEFAULT_MAX_OUTPUT_TOKENS = 512/);
  assert.match(providerSource, /const HARD_MAX_OUTPUT_TOKENS = 1_024/);
  assert.match(tokenBillingSource, /maxRequestTokens, maxOutputTokens/);
});
test("BQ 7-Star and BQ-Codron use the exact requested per-token credit defaults", () => {
  assert.match(source, /model: "gpt-6-luna", credits: \{ inputCreditsPerToken: 0\.000005833333333333333, cachedInputCreditsPerToken: 0\.0000005833333333333333, outputCreditsPerToken: 0\.000029166666666666667 \}/);
  assert.match(source, /model: "gpt-5\.6-luna", credits: \{ inputCreditsPerToken: 0\.000011666666666666668, cachedInputCreditsPerToken: 0\.0000011666666666666668, outputCreditsPerToken: 0\.00007 \}/);
  assert.match(source, /cachedInputImageCreditsPerToken: 0\.00011666666666666667/);
  assert.match(source, /bq_token_credit_pricing_defaults_v5/);
});
