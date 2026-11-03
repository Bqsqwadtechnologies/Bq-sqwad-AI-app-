import test from "node:test";
import assert from "node:assert/strict";
import { calculateTokenCost } from "../src/token-billing-native.js";

test("configured per-token credit prices are final and ignore legacy markup fields", () => {
  const result = calculateTokenCost({
    usage: { inputTokens: 1_000_000, outputTokens: 1_000_000, cachedInputTokens: 0 },
    pricing: {
      inputPriceUsdPerMillion: 1, outputPriceUsdPerMillion: 2, cachedInputPriceUsdPerMillion: 0.5,
      profitMarkupPercent: 600,
      sharedCreditMetering: {
        creditsPerInputToken: 0.000001, creditsPerCachedInputToken: 0.0000001, creditsPerOutputToken: 0.000002,
        minimumCredits: 0, precision: 7, conversionRate: 1,
      },
    },
    multiplier: 8.75,
  });
  assert.equal(result.providerCostUsd, 3);
  assert.equal(result.customerMultiplier, 1);
  assert.equal(result.estimatedFeePercent, 0);
  assert.equal(result.customerCredits, 3);
});

test("cached input uses its own direct credit rate", () => {
  const result = calculateTokenCost({
    usage: { inputTokens: 1_000_000, outputTokens: 0, cachedInputTokens: 400_000 },
    pricing: { sharedCreditMetering: {
      creditsPerInputToken: 0.000001, creditsPerCachedInputToken: 0.0000002, creditsPerOutputToken: 0.000002,
      minimumCredits: 0, precision: 7, conversionRate: 1,
    } },
  });
  assert.equal(result.customerCredits, 0.68);
});

test("missing direct customer credit rates fail closed", () => {
  assert.throws(() => calculateTokenCost({
    usage: { inputTokens: 100, outputTokens: 100, cachedInputTokens: 0 }, pricing: {},
  }), error => error?.message === "token_pricing_not_configured");
});

test("explicit zero direct credit rates are valid and produce zero charge", () => {
  const result = calculateTokenCost({
    usage: { inputTokens: 1200, outputTokens: 800, cachedInputTokens: 300 },
    pricing: { sharedCreditMetering: {
      creditsPerInputToken: 0, creditsPerCachedInputToken: 0, creditsPerOutputToken: 0,
      minimumCredits: 0, precision: 7, conversionRate: 1,
    } },
  });
  assert.equal(result.providerCostUsd, 0);
  assert.equal(result.customerCredits, 0);
});
