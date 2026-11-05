import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { calculateDirectCreditCharge, calculateTokenCreditsFromRule, priceOperation } from "../dynamic-billing.js";

test("configured feature credits are final prices without markup or provider USD", () => {
  const quote = calculateDirectCreditCharge({ credits: 2.75 });
  assert.equal(quote.credits, 2.75);
  assert.equal(quote.reservedCredits, 2.75);
  assert.equal(quote.providerCostUsd, 0);
  assert.equal(quote.sellingPriceUsd, 0);
  assert.equal(quote.profitMarkup, 0);
  assert.equal(quote.feeRateApplied, 0);
  assert.equal(quote.estimateSource, "direct_credit_price");
});

test("zero is a valid direct feature price", () => {
  assert.equal(calculateDirectCreditCharge({ credits: 0 }).credits, 0);
});

test("negative or non-finite direct prices are rejected", () => {
  assert.throws(() => calculateDirectCreditCharge({ credits: -1 }), /direct_credit_price_invalid/);
  assert.throws(() => calculateDirectCreditCharge({ credits: Number.NaN }), /direct_credit_price_invalid/);
});

test("Functions fixed charges read the canonical generation credit rules and unknown features fail closed", async () => {
  const source = await readFile(new URL("../dynamic-billing.js", import.meta.url), "utf8");
  assert.match(source, /collection\("generationCostRules"\)/);
  assert.match(source, /where\("operation", "==", operation\)/);
  assert.match(source, /if \(!operation\) throw new Error\("generation_credit_rule_missing"\)/);
  assert.match(source, /TOKEN_METERED_CAPABILITIES/);
  assert.match(source, /priceTokenUsage/);
  assert.match(source, /collection\("tokenPricingRules"\)/);
  assert.doesNotMatch(source, /calculateDirectCreditCharge\(\{ credits: finalCreditPrice \}\)/);
});

test("Functions calculate actual usage from the saved token rates, including cached image input", () => {
  const rule = {
    inputCreditsPerToken: 0.0002916666666666667,
    cachedInputCreditsPerToken: 0.00007291666666666667,
    outputCreditsPerToken: 0,
    inputImageCreditsPerToken: 0.00046666666666666666,
    cachedInputImageCreditsPerToken: 0.00011666666666666667,
    outputImageCreditsPerToken: 0.00175
  };
  const charge = calculateTokenCreditsFromRule(rule, {
    inputTokens: 1000, cachedInputTokens: 200, inputImageTokens: 300,
    cachedInputImageTokens: 100, outputTokens: 50, outputImageTokens: 10
  });
  const expected = 700 * rule.inputCreditsPerToken + 200 * rule.cachedInputCreditsPerToken +
    200 * rule.inputImageCreditsPerToken + 100 * rule.cachedInputImageCreditsPerToken +
    10 * rule.outputImageCreditsPerToken;
  assert.ok(Math.abs(charge - Math.round(expected * 1e7) / 1e7) < 1e-12);
});

test("token usage without a configured modality rate fails closed", () => {
  assert.throws(() => calculateTokenCreditsFromRule({
    inputCreditsPerToken: 0.0001, cachedInputCreditsPerToken: 0, outputCreditsPerToken: 0
  }, { inputTokens: 10, inputImageTokens: 2 }), /token_pricing_not_configured/);
});

test("unpriced capabilities do not fall back to caller-supplied guesses", async () => {
  await assert.rejects(priceOperation("business_plan_builder", 4.5), /generation_credit_rule_missing/);
});
