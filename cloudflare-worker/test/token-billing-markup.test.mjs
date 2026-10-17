import test from "node:test";
import assert from "node:assert/strict";
import { calculateTokenCost } from "../src/token-billing-native.js";

test("token billing charges provider cost plus exactly 80 percent", () => {
  const result = calculateTokenCost({
    usage: { inputTokens: 1_000_000, outputTokens: 1_000_000, cachedInputTokens: 0 },
    pricing: {
      inputPriceUsdPerMillion: 1,
      outputPriceUsdPerMillion: 2,
      cachedInputPriceUsdPerMillion: 0.5,
      estimatedFeePercent: 0
    },
    creditReferenceValueUsd: 0.07999,
    multiplier: 8
  });

  assert.equal(result.providerCostUsd, 3);
  assert.ok(Math.abs(result.customerCredits - (3 / 0.07999) * 1.8) < 1e-8);
  assert.ok(Math.abs(result.grossUsageValueUsd - 5.4) < 1e-8);
  assert.ok(Math.abs(result.estimatedGrossProfitUsd - 2.4) < 1e-8);
});

test("legacy shared metering cannot override provider-cost-plus-80-percent billing", () => {
  const result = calculateTokenCost({
    usage: { inputTokens: 1_000_000, outputTokens: 1_000_000, cachedInputTokens: 0 },
    pricing: {
      inputPriceUsdPerMillion: 1,
      outputPriceUsdPerMillion: 2,
      estimatedFeePercent: 0,
      sharedCreditMetering: {
        creditsPerInputToken: 0.000001,
        creditsPerOutputToken: 0.000001,
        minimumCredits: 1,
        precision: 2,
        conversionRate: 1
      }
    },
    creditReferenceValueUsd: 0.07999
  });

  assert.ok(Math.abs(result.customerCredits - (3 / 0.07999) * 1.8) < 1e-8);
});
