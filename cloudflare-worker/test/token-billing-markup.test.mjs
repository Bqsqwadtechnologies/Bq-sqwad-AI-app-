import test from "node:test";
import assert from "node:assert/strict";
import { calculateTokenCost } from "../src/token-billing-native.js";

const CREDIT_VALUE_USD = 11.99 / 100;

test("settlement applies 600% markup and the effective model multiplier", () => {
  const result = calculateTokenCost({
    usage: { inputTokens: 1_000_000, outputTokens: 1_000_000, cachedInputTokens: 0 },
    pricing: {
      inputPriceUsdPerMillion: 1,
      outputPriceUsdPerMillion: 2,
      cachedInputPriceUsdPerMillion: 0.5,
      estimatedFeePercent: 0,
      profitMarkupPercent: 600
    },
    creditReferenceValueUsd: CREDIT_VALUE_USD,
    multiplier: 7 * 1.25
  });

  assert.equal(result.providerCostUsd, 3);
  assert.equal(result.creditReferenceValueUsd, CREDIT_VALUE_USD);
  assert.equal(result.customerMultiplier, 8.75);
  const expectedCredits = Math.round(((3 / CREDIT_VALUE_USD) * 8.75 + Number.EPSILON) * 100000) / 100000;
  assert.equal(result.customerCredits, expectedCredits);
  assert.ok(Math.abs(result.grossUsageValueUsd - expectedCredits * CREDIT_VALUE_USD) < 1e-10);
});

test("cached input is charged at its explicit cached-token rate", () => {
  const result = calculateTokenCost({
    usage: { inputTokens: 1_000_000, outputTokens: 0, cachedInputTokens: 400_000 },
    pricing: {
      inputPriceUsdPerMillion: 1,
      outputPriceUsdPerMillion: 2,
      cachedInputPriceUsdPerMillion: 0.2,
      profitMarkupPercent: 600
    },
    creditReferenceValueUsd: CREDIT_VALUE_USD,
    multiplier: 7
  });

  assert.ok(Math.abs(result.providerCostUsd - 0.68) < 1e-10);
  const expectedCredits = Math.round(((0.68 / CREDIT_VALUE_USD) * 7 + Number.EPSILON) * 100000) / 100000;
  assert.equal(result.customerCredits, expectedCredits);
});

test("missing provider token rates fail closed instead of using a generic fallback", () => {
  assert.throws(
    () => calculateTokenCost({
      usage: { inputTokens: 100, outputTokens: 100, cachedInputTokens: 0 },
      pricing: {},
      creditReferenceValueUsd: CREDIT_VALUE_USD,
      multiplier: 7
    }),
    error => error?.message === "token_pricing_not_configured"
  );
});
